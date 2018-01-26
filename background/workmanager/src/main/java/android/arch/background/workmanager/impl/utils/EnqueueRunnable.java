/*
 * Copyright 2018 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.arch.background.workmanager.impl.utils;

import static android.arch.background.workmanager.BaseWork.WorkStatus.BLOCKED;
import static android.arch.background.workmanager.BaseWork.WorkStatus.ENQUEUED;
import static android.arch.background.workmanager.BaseWork.WorkStatus.RUNNING;
import static android.arch.background.workmanager.BaseWork.WorkStatus.SUCCEEDED;

import android.arch.background.workmanager.BaseWork;
import android.arch.background.workmanager.WorkManager;
import android.arch.background.workmanager.impl.InternalWorkImpl;
import android.arch.background.workmanager.impl.WorkContinuationImpl;
import android.arch.background.workmanager.impl.WorkDatabase;
import android.arch.background.workmanager.impl.WorkManagerImpl;
import android.arch.background.workmanager.impl.logger.Logger;
import android.arch.background.workmanager.impl.model.Dependency;
import android.arch.background.workmanager.impl.model.WorkSpec;
import android.arch.background.workmanager.impl.model.WorkSpecDao;
import android.arch.background.workmanager.impl.model.WorkTag;
import android.support.annotation.NonNull;
import android.support.annotation.RestrictTo;
import android.support.annotation.VisibleForTesting;
import android.text.TextUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Manages the enqueuing of a {@link WorkContinuationImpl}.
 *
 * @hide
 */
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
public class EnqueueRunnable implements Runnable {

    private static final String TAG = "EnqueueRunnable";

    private final WorkContinuationImpl mWorkContinuation;
    private final List<InternalWorkImpl> mWorkToBeScheduled;

    public EnqueueRunnable(@NonNull WorkContinuationImpl workContinuation) {
        mWorkContinuation = workContinuation;
        mWorkToBeScheduled = new ArrayList<>();
    }

    @Override
    public void run() {
        addToDatabase();
        scheduleWorkInBackground();
    }

    /**
     * Adds the {@link WorkSpec}'s to the datastore, parent first.
     * Schedules work on the background scheduler, if transaction is successful.
     */
    @VisibleForTesting
    public void addToDatabase() {
        WorkManagerImpl workManagerImpl = mWorkContinuation.getWorkManagerImpl();
        WorkDatabase workDatabase = workManagerImpl.getWorkDatabase();
        workDatabase.beginTransaction();
        try {
            processContinuation(mWorkContinuation, mWorkToBeScheduled);
            workDatabase.setTransactionSuccessful();
        } finally {
            workDatabase.endTransaction();
        }
    }

    /**
     * Schedules work on the background scheduler.
     */
    @VisibleForTesting
    public void scheduleWorkInBackground() {
        // Schedule in the background. This list contains work that does not have prerequisites.
        for (InternalWorkImpl work : mWorkToBeScheduled) {
            mWorkContinuation.getWorkManagerImpl()
                    .getBackgroundScheduler()
                    .schedule(work.getWorkSpec());
        }
    }

    private static void processContinuation(
            @NonNull WorkContinuationImpl workContinuation,
            @NonNull List<InternalWorkImpl> workToBeScheduled) {

        List<WorkContinuationImpl> parents = workContinuation.getParents();
        if (parents != null) {
            for (WorkContinuationImpl parent : parents) {
                // When chaining off a completed continuation we need to pay
                // attention to parents that may have been marked as enqueued before.
                if (!parent.isEnqueued()) {
                    processContinuation(parent, workToBeScheduled);
                } else {
                    Logger.warn(TAG, "Already enqueued work ids (%s).",
                            TextUtils.join(", ", parent.getIds()));
                }
            }
        }
        enqueueContinuation(workContinuation, workToBeScheduled);
    }

    private static void enqueueContinuation(
            @NonNull WorkContinuationImpl workContinuation,
            @NonNull List<InternalWorkImpl> workToBeScheduled) {

        List<WorkContinuationImpl> parents = workContinuation.getParents();
        Set<String> prerequisiteIds = new HashSet<>();
        if (parents != null) {
            for (WorkContinuationImpl parent : parents) {
                prerequisiteIds.addAll(parent.getIds());
            }
        }

        enqueueWorkWithPrerequisites(
                workContinuation.getWorkManagerImpl(),
                workContinuation.getWork(),
                prerequisiteIds.toArray(new String[0]),
                workContinuation.getUniqueTag(),
                workContinuation.getExistingWorkPolicy(),
                workToBeScheduled);

        workContinuation.markEnqueued();
    }

    /**
     * Enqueues the {@link WorkSpec}'s while keeping track of the prerequisites.
     */
    private static void enqueueWorkWithPrerequisites(
            WorkManagerImpl workManagerImpl,
            @NonNull BaseWork[] workArray,
            String[] prerequisiteIds,
            String uniqueTag,
            @WorkManager.ExistingWorkPolicy int existingWorkPolicy,
            @NonNull List<InternalWorkImpl> workToBeScheduled) {

        long currentTimeMillis = System.currentTimeMillis();
        WorkDatabase workDatabase = workManagerImpl.getWorkDatabase();

        InternalWorkImpl[] workImplArray = new InternalWorkImpl[workArray.length];
        for (int i = 0; i < workArray.length; ++i) {
            workImplArray[i] = (InternalWorkImpl) workArray[i];
        }

        boolean hasPrerequisite = (prerequisiteIds != null && prerequisiteIds.length > 0);
        boolean hasCompletedAllPrerequisites = true;

        if (hasPrerequisite) {
            // If there are prerequisites, make sure they actually exist before enqueuing
            // anything.  Prerequisites may not exist if we are using unique tags, because the
            // chain of work could have been wiped out already.
            for (String id : prerequisiteIds) {
                WorkSpec prerequisiteWorkSpec = workDatabase.workSpecDao().getWorkSpec(id);
                if (prerequisiteWorkSpec == null) {
                    Logger.error(TAG, "Prerequisite %s doesn't exist; not enqueuing", id);
                    return;
                }
                hasCompletedAllPrerequisites &=
                        (prerequisiteWorkSpec.getStatus() == SUCCEEDED);
            }
        }

        boolean hasUniqueTag = !TextUtils.isEmpty(uniqueTag);
        if (hasUniqueTag && !hasPrerequisite) {
            List<WorkSpec.IdAndStatus> existingWorkSpecIdAndStatuses =
                    workDatabase.workSpecDao().getWorkSpecIdAndStatusesForTag(uniqueTag);
            if (!existingWorkSpecIdAndStatuses.isEmpty()) {
                if (existingWorkPolicy == WorkManager.KEEP_EXISTING_WORK) {
                    for (WorkSpec.IdAndStatus idAndStatus : existingWorkSpecIdAndStatuses) {
                        if (idAndStatus.status == ENQUEUED || idAndStatus.status == RUNNING) {
                            return;
                        }
                    }
                }

                // Cancel all of these workers.
                CancelWorkRunnable cancelWorkRunnable =
                        new CancelWorkRunnable(workManagerImpl, null, uniqueTag);
                cancelWorkRunnable.run();
                // And delete all the database records.
                WorkSpecDao workSpecDao = workDatabase.workSpecDao();
                for (WorkSpec.IdAndStatus idAndStatus : existingWorkSpecIdAndStatuses) {
                    workSpecDao.delete(idAndStatus.id);
                }
            }
        }

        for (InternalWorkImpl work : workImplArray) {
            WorkSpec workSpec = work.getWorkSpec();

            if (hasPrerequisite && !hasCompletedAllPrerequisites) {
                workSpec.setStatus(BLOCKED);
            } else {
                // Set scheduled times only for work without prerequisites. Dependent work
                // will set their scheduled times when they are unblocked.
                workSpec.setPeriodStartTime(currentTimeMillis);
            }

            workDatabase.workSpecDao().insertWorkSpec(workSpec);

            if (hasPrerequisite) {
                for (String prerequisiteId : prerequisiteIds) {
                    Dependency dep = new Dependency(work.getId(), prerequisiteId);
                    workDatabase.dependencyDao().insertDependency(dep);
                }
            }

            for (String tag : work.getTags()) {
                workDatabase.workTagDao().insert(new WorkTag(tag, work.getId()));
            }

            // Enforce that the unique tag is always present.
            if (hasUniqueTag) {
                workDatabase.workTagDao().insert(new WorkTag(uniqueTag, work.getId()));
            }
        }

        // If there are no prerequisites, then add then to the list of work items
        // that can be scheduled in the background. The actual scheduling is typically
        // done after the transaction has settled.
        if (!hasPrerequisite) {
            workToBeScheduled.addAll(Arrays.asList(workImplArray));
        }
    }
}
