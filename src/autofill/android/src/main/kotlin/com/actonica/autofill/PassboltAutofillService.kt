// ©2019-2020 Actonica LLC - All Rights Reserved

package com.actonica.autofill

import android.app.PendingIntent
import android.app.assist.AssistStructure
import android.app.assist.AssistStructure.ViewNode
import android.content.Intent
import android.content.IntentSender
import android.os.CancellationSignal
import android.os.Parcel
import android.service.autofill.AutofillService
import android.service.autofill.FillCallback
import android.service.autofill.FillRequest
import android.service.autofill.FillResponse
import android.service.autofill.SaveCallback
import android.service.autofill.SaveRequest
import android.text.InputType
import android.util.Log
import android.view.View
import android.view.autofill.AutofillId
import android.widget.RemoteViews
import java.util.*

class PassboltAutofillService : AutofillService() {

    companion object {
        const val TAG: String = "PassboltAutofillService"
        const val INTENT_EXTRAS_DATASET = "intent_extras_dataset"
        const val INTENT_EXTRAS_AUTOFILLIDS = "intent_extras_autofillids_parcels"
    }

    var autofillIdPackage: String? = null
    var autofillWebDomain: String? = null

    override fun onFillRequest(
            request: FillRequest,
            cancellationSignal: CancellationSignal,
            callback: FillCallback
    ) {
        val latestAssistStructure: AssistStructure? =
                try {
                    request.fillContexts[request.fillContexts.size - 1].structure
                } catch (e: Throwable) {
                    e.printStackTrace()
                    null
                }

        Log.d(TAG, "onFillRequest latestAssistStructure: $latestAssistStructure")

        if (latestAssistStructure != null) {
            val fields: MutableMap<String, AutofillId> =
                    getAutofillableFields(latestAssistStructure)

            if (fields.isEmpty() || fields.keys.contains("password").not()) {
                return
            } else {
                Log.d(TAG, "onFillRequest build response")
                val response = FillResponse.Builder()

                val autoFillHints = hashMapOf<String, Any>()

                autofillIdPackage?.let {
                    autoFillHints["autofillIdPackage"] = it
                }

                autofillWebDomain?.let {
                    autoFillHints["autofillWebDomain"] = it
                }

                val hintsList = mutableListOf<HashMap<String, String>>()
                val parcelsArray = mutableListOf<Parcel>()

                fields.forEach { (hint: String, autofillId: AutofillId) ->
                    val hintMap = hashMapOf<String, String>()
                    hintMap["hint"] = hint

                    val autofillIdParcel = Parcel.obtain()
                    autofillId.writeToParcel(autofillIdParcel, 0)
                    autofillIdParcel.setDataPosition(0)
                    parcelsArray.add(autofillIdParcel)

                    hintMap["autofillId"] = autofillId.toString()

                    Log.v(TAG, "autofillHint hint: $hint, autofillId: $autofillId")

                    hintsList.add(hintMap)
                }

                autoFillHints["hints"] = hintsList

                val authIntent = Intent(this, Class.forName("com.actonica.passbolt.MainActivity"))
                authIntent.putExtra(INTENT_EXTRAS_DATASET, autoFillHints)
                authIntent.putExtra(INTENT_EXTRAS_AUTOFILLIDS, fields.values.toTypedArray())

                val intentSender: IntentSender = PendingIntent.getActivity(
                        this,
                        1001,
                        authIntent,
                        PendingIntent.FLAG_CANCEL_CURRENT
                ).intentSender

                response.setAuthentication(
                    fields.values.toTypedArray(),
                    intentSender,
                    buildInvocationRemoteViews(packageName, "Autofill with Passbolt")
                )

                callback.onSuccess(response.build())
            }
        }
    }

    override fun onSaveRequest(saveRequest: SaveRequest, saveCallback: SaveCallback) {
        // no op
        Log.d(TAG, "onSaveRequest")
    }

    private fun getAutofillableFields(structure: AssistStructure): MutableMap<String, AutofillId> {
        val fields = mutableMapOf<String, AutofillId>()
        val nodes = structure.windowNodeCount
        for (i in 0 until nodes) {
            val viewNode: ViewNode = structure.getWindowNodeAt(i).rootViewNode
            Log.d(TAG, "onFillRequest addAutofillableFieldsWithHints viewNode $viewNode")
            addAutofillableFieldsWithHints(fields, viewNode)
        }

        return fields
    }

    private fun addAutofillableFieldsWithHints(
            fields: MutableMap<String, AutofillId>,
            node: ViewNode
    ) {
        Log.v(
                TAG,
                "viewNode package ${node.idPackage} domain ${node.webDomain}"
        )

        if (this.autofillIdPackage == null && node.idPackage != null) {
            this.autofillIdPackage = node.idPackage
        }

        if (this.autofillWebDomain == null && node.webDomain != null) {
            this.autofillWebDomain = node.webDomain
        }

        val hint: String? = getHint(node)
        if (hint != null && node.autofillId != null) {
            val id = node.autofillId!!
            if (!fields.containsKey(hint)) {
                Log.v(TAG, "Setting hint '$hint' on $id")
                fields[hint] = id
            } else {
                Log.v(
                        TAG,
                        "Ignoring hint '" + hint + "' on " + id
                                + " because it was already set"
                )
            }
        }
        val childrenSize = node.childCount
        for (i in 0 until childrenSize) {
            addAutofillableFieldsWithHints(fields, node.getChildAt(i))
        }
    }

    private fun getHint(node: ViewNode): String? {
        val hints = node.autofillHints
        if (!hints.isNullOrEmpty()) {
            Log.v(TAG, "Found hint using node.autofillHints ${node.autofillHints?.joinToString()}")
            return hints[0].toLowerCase(Locale.getDefault())
        }

        var hint: String?
        if (node.inputType == (InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
                || node.inputType == (InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD)
                || node.inputType == (InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD)) {
            Log.v(TAG, "Found hint using node.inputType: ${node.inputType}")
            hint = View.AUTOFILL_HINT_PASSWORD
            return hint
        } else if (node.inputType == (InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS)
                || node.inputType == (InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS)) {
            Log.v(TAG, "Found hint using node.inputType: ${node.inputType}")
            hint = View.AUTOFILL_HINT_EMAIL_ADDRESS
            return hint
        } else {
            Log.v(TAG, "No hint using node.inputType: ${node.inputType}")
        }

        val viewHint: String? = node.hint
        hint = inferHint(node, viewHint)
        if (!hint.isNullOrEmpty()) {
            Log.v(TAG, "Found hint using viewHint: $viewHint hint: $hint")
            return hint
        } else {
            Log.v(TAG, "No hint using viewHint: $viewHint")
        }

        val text = node.text
        val className: CharSequence? = node.className
        if (text != null && className != null && className.toString().contains("EditText")) {
            hint = inferHint(node, text.toString())
            if (hint != null) {
                Log.v(TAG, "Found hint using text: $hint")
                return hint
            }
        } else {
            Log.v(TAG, "No hint using text and class $className")
        }

        val resourceId = node.idEntry
        hint = inferHint(node, resourceId)
        if (!hint.isNullOrEmpty()) {
            Log.v(TAG, "Found hint using resourceId($resourceId): $hint")
            return hint
        } else {
            Log.v(TAG, "No hint using resourceId: $resourceId")
        }

        return null
    }

    private fun inferHint(node: ViewNode, actualHint: String?): String? {
        if (actualHint == null) return null
        val hint = actualHint.toLowerCase(Locale.getDefault())
        if (hint.contains("label") || hint.contains("container")) {
            Log.v(TAG, "Ignoring 'label/container' hint: $hint")
            return null
        }
        if (hint.contains("password") || hint.contains("пароль")) return View.AUTOFILL_HINT_PASSWORD
        if (hint.contains("username")
                || hint.contains("login")
                || hint.contains("пользовател")
        ) return View.AUTOFILL_HINT_USERNAME
        if (hint.contains("email") || hint.contains("e-mail") || hint.contains("почта")) return View.AUTOFILL_HINT_EMAIL_ADDRESS
        if (hint.contains("name") || hint.contains("имя")) return View.AUTOFILL_HINT_NAME
        if (hint.contains("phone") || hint.contains("телефон")) return View.AUTOFILL_HINT_PHONE
        return null
    }

    private fun buildInvocationRemoteViews(packageName: String, text: CharSequence): RemoteViews {
        val presentation =
                RemoteViews(packageName, R.layout.dataset_remote_views)
        presentation.setTextViewText(R.id.fieldDescription, text)
        presentation.setImageViewResource(R.id.appIcon, R.mipmap.ic_launcher)
        return presentation
    }
}