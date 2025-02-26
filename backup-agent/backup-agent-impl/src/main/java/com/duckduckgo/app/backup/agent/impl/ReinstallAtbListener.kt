/*
 * Copyright (c) 2023 DuckDuckGo
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.duckduckgo.app.backup.agent.impl

import com.duckduckgo.app.backup.agent.impl.BackupAgentManagerImpl.Companion.REINSTALL_VARIANT
import com.duckduckgo.app.backup.agent.impl.store.BackupDataStore
import com.duckduckgo.app.statistics.AtbInitializerListener
import com.duckduckgo.app.statistics.store.StatisticsDataStore
import com.duckduckgo.di.scopes.AppScope
import com.squareup.anvil.annotations.ContributesMultibinding
import dagger.SingleInstanceIn
import javax.inject.Inject
import timber.log.Timber

@SingleInstanceIn(AppScope::class)
@ContributesMultibinding(AppScope::class)
class ReinstallAtbListener @Inject constructor(
    private val statisticsDataStore: StatisticsDataStore,
    private val backupDataStore: BackupDataStore,
) : AtbInitializerListener {
    override suspend fun beforeAtbInit() {
        if (statisticsDataStore.hasInstallationStatistics && backupDataStore.atb != statisticsDataStore.atb) {
            backupDataStore.atb = statisticsDataStore.atb
        }
        if (!statisticsDataStore.hasInstallationStatistics && backupDataStore.atb != null) {
            backupDataStore.atb = null
            statisticsDataStore.variant = REINSTALL_VARIANT
            Timber.d("Variant update for returning user")
        }
    }

    override fun beforeAtbInitTimeoutMillis(): Long = MAX_REINSTALL_WAIT_TIME_MS

    companion object {
        private const val MAX_REINSTALL_WAIT_TIME_MS = 1_500L
    }
}
