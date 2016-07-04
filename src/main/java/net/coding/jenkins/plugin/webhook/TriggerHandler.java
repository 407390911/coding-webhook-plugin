package net.coding.jenkins.plugin.webhook;

import net.coding.jenkins.plugin.CodingPushTrigger;
import net.coding.jenkins.plugin.cause.CauseData;
import net.coding.jenkins.plugin.cause.CodingWebHookCause;
import net.coding.jenkins.plugin.model.Commit;
import net.coding.jenkins.plugin.model.MergeRequest;
import net.coding.jenkins.plugin.model.PullRequest;
import net.coding.jenkins.plugin.model.WebHook;
import net.coding.jenkins.plugin.webhook.filter.BranchFilter;

import org.apache.commons.lang.StringUtils;
import org.eclipse.jgit.transport.URIish;

import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import hudson.model.AbstractProject;
import hudson.model.Action;
import hudson.model.CauseAction;
import hudson.model.Job;
import hudson.plugins.git.RevisionParameterAction;
import jenkins.model.ParameterizedJobMixIn;

import static org.eclipse.jgit.lib.Repository.shortenRefName;
import static net.coding.jenkins.plugin.cause.CauseData.ActionType;

/**
 * @author tsl0922
 */
public class TriggerHandler {
    private final static Logger LOGGER = Logger.getLogger(TriggerHandler.class.getName());

    private static final String PUSH_EVENT = "push";
    private static final String PULL_REQUEST_EVENT = "pull_request";
    private static final String MERGE_REQUEST_EVENT = "merge_request";

    private static final String NO_COMMIT = "0000000000000000000000000000000000000000";
    private static final String CI_SKIP = "[ci-skip]";

    private boolean triggerOnPush;
    private boolean triggerOnMergeRequest;
    private BranchFilter branchFilter;

    public TriggerHandler(boolean triggerOnPush, boolean triggerOnMergeRequest, BranchFilter branchFilter) {
        this.triggerOnPush = triggerOnPush;
        this.triggerOnMergeRequest = triggerOnMergeRequest;
        this.branchFilter = branchFilter;
    }

    public void handle(Job<?, ?> job, WebHook hook, String event, boolean ciSkip) {
        if (job instanceof AbstractProject<?, ?>) {
            AbstractProject<?, ?> project = (AbstractProject<?, ?>) job;
            final CodingPushTrigger trigger = project.getTrigger(CodingPushTrigger.class);
            if (trigger != null) {
                boolean shouldTrigger = false;
                CauseData.ActionType actionType = null;
                String branch = null;
                switch (event) {
                    case PUSH_EVENT:
                        if (isNoRemoveBranchPush(hook)) {
                            shouldTrigger = triggerOnPush;
                            actionType = ActionType.PUSH;
                            branch = shortenRef(hook.getRef());
                        }
                        break;
                    case MERGE_REQUEST_EVENT:
                        MergeRequest mr = hook.getMerge_request();
                        if (mr.isCreate()) {
                            shouldTrigger = triggerOnMergeRequest;
                            actionType = ActionType.MR;
                            branch = mr.getTarget_branch();
                        }
                        break;
                    case PULL_REQUEST_EVENT:
                        PullRequest pr = hook.getPull_request();
                        if (pr.isCreate()) {
                            shouldTrigger = triggerOnMergeRequest;
                            actionType = ActionType.PR;
                            branch = pr.getTarget_branch();
                        }
                        break;
                    default:
                        break;
                }
                if (ciSkip && isCiSkip(hook, actionType)) {
                    LOGGER.log(Level.INFO, "Skipping due to ci-skip.");
                    return;
                }
                if (!branchFilter.isBranchAllowed(branch)) {
                    LOGGER.log(Level.INFO, "Branch {0} is not allowed", branch);
                    return;
                }
                if (shouldTrigger) {
                    scheduleBuild(job, createActions(job, hook, actionType));
                }
            }
        }
    }

    private void scheduleBuild(Job<?, ?> job, Action[] actions) {
        int projectBuildDelay = 0;
        if (job instanceof ParameterizedJobMixIn.ParameterizedJob) {
            ParameterizedJobMixIn.ParameterizedJob abstractProject = (ParameterizedJobMixIn.ParameterizedJob) job;
            if (abstractProject.getQuietPeriod() > projectBuildDelay) {
                projectBuildDelay = abstractProject.getQuietPeriod();
            }
        }
        asParameterizedJobMixIn(job).scheduleBuild2(projectBuildDelay, actions);
    }

    private Action[] createActions(Job<?, ?> job, WebHook hook, CauseData.ActionType actionType) {
        List<Action> actions = new ArrayList<>();
        actions.add(new CauseAction(new CodingWebHookCause(buildCauseData(hook, actionType))));
        try {
            actions.add(createRevisionParameter(hook, actionType));
        } catch (IllegalStateException e) {
            LOGGER.log(Level.WARNING, "Unable to build for req {0} for job {1}: {2}",
                    new Object[]{hook, (job != null ? job.getFullName() : null), e.getMessage()});
        }
        return actions.toArray(new Action[actions.size()]);
    }

    private RevisionParameterAction createRevisionParameter(WebHook hook, CauseData.ActionType actionType) {
        return new RevisionParameterAction(retrieveRevisionToBuild(hook, actionType), createUrIish(hook));
    }

    private String retrieveRevisionToBuild(WebHook hook, ActionType actionType) {
        String revision = null;
        switch (actionType) {
            case PUSH:
                if ((hook.getCommits() == null || hook.getCommits().isEmpty()) && isNewBranchPush(hook)) {
                    revision = hook.getAfter();
                } else {
                    List<Commit> commits = hook.getCommits();
                    revision = commits.get(commits.size() - 1).getSha();
                }
                break;
            case MR:
                revision = hook.getMerge_request().getMerge_commit_sha();
                break;
            case PR:
                revision = hook.getPull_request().getMerge_commit_sha();
            default:
                break;
        }
        if (StringUtils.isEmpty(revision)) {
            throw new IllegalStateException("No revision to build");
        }
        return revision;
    }

    private CauseData buildCauseData(WebHook hook, CauseData.ActionType actionType) {
        CauseData data = new CauseData();
        data.setActionType(actionType);
        data.setToken(hook.getToken());
        if (hook.getUser() != null) {
            data.setUserName(hook.getUser().getName());
            data.setUserUrl(hook.getUser().getWeb_url());
        }
        data.setRef(hook.getRef());
        data.setBefore(hook.getBefore());
        data.setAfter(hook.getAfter());
        data.setRepoUrl(hook.getRepository().getSsh_url());
        if (hook.getMerge_request() != null) {
            MergeRequest mr = hook.getMerge_request();
            data.setMergeRequestIid(mr.getNumber());
            data.setMergeRequestTitle(mr.getTitle());
            data.setMergeRequestBody(mr.getBody());
            data.setMergeRequestUrl(mr.getWeb_url());
            data.setSourceBranch(shortenRef(mr.getSource_branch()));
            data.setTargetBranch(shortenRef(mr.getTarget_branch()));
        }
        if (hook.getPull_request() != null) {
            PullRequest pr = hook.getPull_request();
            data.setMergeRequestIid(pr.getNumber());
            data.setMergeRequestTitle(pr.getTitle());
            data.setMergeRequestBody(pr.getBody());
            data.setMergeRequestUrl(pr.getWeb_url());
            data.setSourceBranch(shortenRef(pr.getSource_branch()));
            data.setSourceRepoUrl(pr.getSource_repository().getSsh_url());
            data.setSourceUser(pr.getSource_repository().getOwner().getGlobal_key());
            data.setTargetBranch(shortenRef(pr.getTarget_branch()));
        }
        return data;
    }

    private String shortenRef(String ref) {
        return ref == null ? null : shortenRefName(ref);
    }

    private static <T extends Job> ParameterizedJobMixIn asParameterizedJobMixIn(final T job) {
        return new ParameterizedJobMixIn() {
            @Override
            protected Job asJob() {
                return job;
            }
        };
    }

    private URIish createUrIish(WebHook hook) {
        try {
            if (hook.getRepository() != null) {
                return new URIish(hook.getRepository().getSsh_url());
            }
        } catch (URISyntaxException e) {
            LOGGER.log(Level.WARNING, "could not parse URL");
        }
        return null;
    }

    private boolean isNewBranchPush(WebHook hook) {
        return hook.getBefore() != null && hook.getBefore().equals(NO_COMMIT);
    }

    private boolean isNoRemoveBranchPush(WebHook hook) {
        return hook.getAfter() != null && !hook.getAfter().equals(NO_COMMIT);
    }

    private boolean isCiSkip(WebHook hook, ActionType actionType) {
        switch (actionType) {
            case PUSH:
                List<Commit> commits = hook.getCommits();
                return commits != null &&
                        !commits.isEmpty() &&
                        commits.get(commits.size() - 1).getShort_message() != null &&
                        StringUtils.containsIgnoreCase(commits.get(commits.size() - 1).getShort_message(), CI_SKIP);
            case MR:
                MergeRequest mr = hook.getMerge_request();
                return StringUtils.containsIgnoreCase(mr.getTitle(), CI_SKIP);
            case PR:
                PullRequest pr = hook.getPull_request();
                return StringUtils.containsIgnoreCase(pr.getTitle(), CI_SKIP);
            default:
                return false;
        }
    }
}
