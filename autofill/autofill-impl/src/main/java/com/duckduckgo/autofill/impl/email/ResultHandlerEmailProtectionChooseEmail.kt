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

package com.duckduckgo.autofill.impl.email

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.Parcelable
import androidx.fragment.app.Fragment
import com.duckduckgo.app.di.AppCoroutineScope
import com.duckduckgo.app.global.DispatcherProvider
import com.duckduckgo.appbuildconfig.api.AppBuildConfig
import com.duckduckgo.autofill.api.AutofillEventListener
import com.duckduckgo.autofill.api.AutofillFragmentResultsPlugin
import com.duckduckgo.autofill.api.EmailProtectionChooserDialog
import com.duckduckgo.autofill.api.EmailProtectionChooserDialog.UseEmailResultType
import com.duckduckgo.autofill.api.EmailProtectionChooserDialog.UseEmailResultType.DoNotUseEmailProtection
import com.duckduckgo.autofill.api.EmailProtectionChooserDialog.UseEmailResultType.UsePersonalEmailAddress
import com.duckduckgo.autofill.api.EmailProtectionChooserDialog.UseEmailResultType.UsePrivateAliasAddress
import com.duckduckgo.autofill.api.email.EmailManager
import com.duckduckgo.di.scopes.AppScope
import com.squareup.anvil.annotations.ContributesMultibinding
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

@ContributesMultibinding(AppScope::class)
class ResultHandlerEmailProtectionChooseEmail @Inject constructor(
    private val appBuildConfig: AppBuildConfig,
    private val emailManager: EmailManager,
    private val dispatchers: DispatcherProvider,
    @AppCoroutineScope private val appCoroutineScope: CoroutineScope,
) : AutofillFragmentResultsPlugin {

    override fun processResult(
        result: Bundle,
        context: Context,
        tabId: String,
        fragment: Fragment,
        autofillCallback: AutofillEventListener,
    ) {
        Timber.d("${this::class.java.simpleName}: processing result")

        val userSelection: UseEmailResultType =
            result.safeGetParcelable(EmailProtectionChooserDialog.KEY_RESULT) ?: return
        val originalUrl = result.getString(EmailProtectionChooserDialog.KEY_URL) ?: return

        when (userSelection) {
            UsePersonalEmailAddress -> onSelectedToUsePersonalAddress(originalUrl, autofillCallback)
            UsePrivateAliasAddress -> onSelectedToUsePrivateAlias(originalUrl, autofillCallback)
            DoNotUseEmailProtection -> onSelectedNotToUseEmailProtection(originalUrl, autofillCallback)
        }
    }

    private fun onSelectedToUsePersonalAddress(originalUrl: String, autofillCallback: AutofillEventListener) {
        appCoroutineScope.launch(dispatchers.io()) {
            val duckAddress = emailManager.getEmailAddress() ?: return@launch

            withContext(dispatchers.main()) {
                autofillCallback.onUseEmailProtectionPersonalAddress(originalUrl, duckAddress)
            }

            emailManager.setNewLastUsedDate()
        }
    }

    private fun onSelectedToUsePrivateAlias(originalUrl: String, autofillCallback: AutofillEventListener) {
        appCoroutineScope.launch(dispatchers.io()) {
            val privateAlias = emailManager.getAlias() ?: return@launch

            withContext(dispatchers.main()) {
                autofillCallback.onUseEmailProtectionPrivateAlias(originalUrl, privateAlias)
            }

            emailManager.setNewLastUsedDate()
        }
    }

    private fun onSelectedNotToUseEmailProtection(originalUrl: String, autofillCallback: AutofillEventListener) {
        appCoroutineScope.launch(dispatchers.main()) {
            autofillCallback.onRejectToUseEmailProtection(originalUrl)
        }
    }

    @Suppress("DEPRECATION")
    @SuppressLint("NewApi")
    private inline fun <reified T : Parcelable> Bundle.safeGetParcelable(key: String) =
        if (appBuildConfig.sdkInt >= Build.VERSION_CODES.TIRAMISU) {
            getParcelable(key, T::class.java)
        } else {
            getParcelable(key)
        }

    override fun resultKey(tabId: String): String {
        return EmailProtectionChooserDialog.resultKey(tabId)
    }
}