package com.axiom.voice.utilities

import android.util.Log
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.QueryPurchasesParams
import com.android.billingclient.api.queryPurchasesAsync
import com.axiom.voice.MyApplication
import com.google.firebase.Firebase
import com.google.firebase.Timestamp
import com.google.firebase.auth.auth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.firestore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Calendar

class FreemiumManager {

    private val db = Firebase.firestore
    private val auth = Firebase.auth
    private val billingClient: BillingClient = MyApplication.billingClient

    companion object {
        const val DAILY_TASK_LIMIT = 15 // Set your daily task limit here
        private const val PRO_SKU = "pro" // The SKU for the pro subscription
    }

    suspend fun getDeveloperMessage(): String {
        return try {
            val document = db.collection("settings").document("freemium").get().await()
            document.getString("developerMessage") ?: ""
        } catch (e: Exception) {
            Log.e("FreemiumManager", "Error fetching developer message from Firestore.", e)
            ""
        }
    }
    suspend fun isUserSubscribed(): Boolean {
        val currentUser = auth.currentUser ?: return false // If not logged in, not pro
        return try {
            val document = withTimeoutOrNull(5000) {
                db.collection("users").document(currentUser.uid).get().await()
            }
            if (document?.exists() == true) {
                val plan = document.getString("plan")
                plan == "pro"
            } else {
                false // Document doesn't exist or timeout, so user can't be pro
            }
        } catch (e: Exception) {
            Logger.d("FreemiumManager", "Offline or network error, defaulting to free plan")
            false // In case of error, default to not pro
        }
    }



    suspend fun provisionUserIfNeeded() {
        val currentUser = auth.currentUser ?: return
        val userDocRef = db.collection("users").document(currentUser.uid)

        try {
            val document = userDocRef.get().await()
            if (!document.exists()) {
                Logger.d("FreemiumManager", "Provisioning new user: ${currentUser.uid}")
                val newUser = hashMapOf(
                    "email" to currentUser.email,
                    "plan" to "free",
                    "createdAt" to FieldValue.serverTimestamp()
                )
                userDocRef.set(newUser).await()
            }
        } catch (e: Exception) {
            Logger.e("FreemiumManager", "Error provisioning user", e)
        }
    }

    suspend fun getTasksRemaining(): Long? {
        if (isUserSubscribed()) return Long.MAX_VALUE
        val currentUser = auth.currentUser ?: return null
        return try {
            val document = withTimeoutOrNull(5000) {
                db.collection("users").document(currentUser.uid).get().await()
            }
            document?.getLong("tasksRemaining") ?: DAILY_TASK_LIMIT.toLong()
        } catch (e: Exception) {
            Logger.d("FreemiumManager", "Offline mode, using default task limit")
            DAILY_TASK_LIMIT.toLong()
        }
    }

    suspend fun canPerformTask(): Boolean {
        if (isUserSubscribed()) return true
        val currentUser = auth.currentUser ?: return false

        return try {
            val document = withTimeoutOrNull(5000) {
                db.collection("users").document(currentUser.uid).get().await()
            }
            val tasksRemaining = document?.getLong("tasksRemaining") ?: DAILY_TASK_LIMIT.toLong()
            Logger.d("FreemiumManager", "User has $tasksRemaining tasks remaining today.")
            tasksRemaining > 0
        } catch (e: Exception) {
            Logger.d("FreemiumManager", "Offline mode, allowing task execution")
            true // Allow tasks in offline mode
        }
    }

    suspend fun decrementTaskCount() {
        if (isUserSubscribed()) return
        val currentUser = auth.currentUser ?: return

        val userDocRef = db.collection("users").document(currentUser.uid)
        
        try {
            withTimeoutOrNull(5000) {
                userDocRef.update("tasksRemaining", FieldValue.increment(-1)).await()
            }
            Logger.d("FreemiumManager", "Successfully decremented task count for user ${currentUser.uid}.")
        } catch (e: Exception) {
            Logger.d("FreemiumManager", "Offline mode, task count not decremented")
        }
    }
}
