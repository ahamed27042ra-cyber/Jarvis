package com.jarvis.assistant.ui.auth

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Build
import android.provider.Settings
import android.text.SpannableString
import android.text.Spanned
import android.text.TextPaint
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.util.Log
import android.view.View
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import com.jarvis.assistant.R
import com.jarvis.assistant.service.FloatingOrbService
import com.jarvis.assistant.ui.legal.PrivacyPolicyActivity
import com.jarvis.assistant.ui.legal.TermsActivity
import com.jarvis.assistant.ui.main.MainActivity
import com.jarvis.assistant.ui.main.OrbAnimationView
import com.jarvis.assistant.util.ThemeManager

class LoginActivity : AppCompatActivity() {

    private lateinit var googleSignInClient: GoogleSignInClient
    private lateinit var firebaseAuth: FirebaseAuth

    private lateinit var loginOrbView: OrbAnimationView
    private lateinit var termsCheckBox: CheckBox
    private lateinit var termsText: TextView
    private lateinit var googleSignInBtn: FrameLayout
    private lateinit var signInProgressBar: ProgressBar

    private val googleSignInLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        signInProgressBar.visibility = View.GONE
        googleSignInBtn.isEnabled = termsCheckBox.isChecked
        val data = result.data ?: run {
            showError("Google Sign-In was cancelled.")
            return@registerForActivityResult
        }

        try {
            val account = GoogleSignIn.getSignedInAccountFromIntent(data)
                .getResult(ApiException::class.java)
            val name = account.displayName ?: account.givenName ?: account.email?.substringBefore("@") ?: "Jarvis User"
            onAuthSuccess(name, account.email.orEmpty(), account.photoUrl?.toString().orEmpty(), account.idToken)
        } catch (e: ApiException) {
            Log.e("LoginActivity", "Google Sign-In failed: statusCode=" + e.statusCode, e)
            GoogleSignIn.getLastSignedInAccount(this)?.let { account ->
                onAuthSuccess(account.displayName ?: account.givenName ?: account.email?.substringBefore("@") ?: "Jarvis User", account.email.orEmpty(), account.photoUrl?.toString().orEmpty(), account.idToken)
            } ?: showError("Google Sign-In failed (code " + e.statusCode + "). Please try again.")
        } catch (e: Exception) {
            Log.e("LoginActivity", "Google Sign-In result processing failed", e)
            showError("Google Sign-In failed. Please try again.")
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.applyTheme(this)
        super.onCreate(savedInstanceState)

        firebaseAuth = FirebaseAuth.getInstance()

        if (isLocallyAuthenticated()) {
            launchNextScreen()
            return
        }

        setContentView(R.layout.activity_login)

        loginOrbView = findViewById(R.id.loginOrbView)
        termsCheckBox = findViewById(R.id.termsCheckBox)
        termsText = findViewById(R.id.termsText)
        googleSignInBtn = findViewById(R.id.googleSignInBtn)
        signInProgressBar = findViewById(R.id.signInProgressBar)

        loginOrbView.setState(OrbAnimationView.OrbState.LOGIN)
        loginOrbView.setAmplitude(0.35f)

        setupTermsTextWithLinks()
        setupGoogleSignInClient()

        termsCheckBox.setOnCheckedChangeListener { _, isChecked ->
            googleSignInBtn.isEnabled = isChecked
            googleSignInBtn.alpha = if (isChecked) 1.0f else 0.45f
        }

        googleSignInBtn.setOnClickListener {
            if (!termsCheckBox.isChecked) {
                Toast.makeText(this, "Please agree to the Terms & Conditions and Privacy Policy", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            startGoogleSignIn()
        }
    }

    private fun setupTermsTextWithLinks() {
        val fullText = "I agree to the Terms & Conditions and Privacy Policy"
        val spannable = SpannableString(fullText)

        val accentColor = Color.parseColor(ThemeManager.getSecondaryColorHex(this))
        val termsSpan = object : ClickableSpan() {
            override fun onClick(widget: View) {
                startActivity(Intent(this@LoginActivity, TermsActivity::class.java))
            }
            override fun updateDrawState(ds: TextPaint) {
                ds.color = accentColor
                ds.isUnderlineText = true
            }
        }

        val privacySpan = object : ClickableSpan() {
            override fun onClick(widget: View) {
                startActivity(Intent(this@LoginActivity, PrivacyPolicyActivity::class.java))
            }
            override fun updateDrawState(ds: TextPaint) {
                ds.color = accentColor
                ds.isUnderlineText = true
            }
        }

        val termsStart = fullText.indexOf("Terms & Conditions")
        if (termsStart != -1) {
            spannable.setSpan(termsSpan, termsStart, termsStart + "Terms & Conditions".length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }

        val privacyStart = fullText.indexOf("Privacy Policy")
        if (privacyStart != -1) {
            spannable.setSpan(privacySpan, privacyStart, privacyStart + "Privacy Policy".length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }

        termsText.text = spannable
        termsText.movementMethod = LinkMovementMethod.getInstance()
    }

    private fun setupGoogleSignInClient() {
        val webClientId = getString(R.string.default_web_client_id)
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestIdToken(webClientId)
            .requestEmail()
            .requestProfile()
            .build()
        googleSignInClient = GoogleSignIn.getClient(this, gso)
    }

    private fun startGoogleSignIn() {
        signInProgressBar.visibility = View.VISIBLE
        googleSignInBtn.isEnabled = false
        googleSignInClient.signOut().addOnCompleteListener {
            val signInIntent = googleSignInClient.signInIntent
            googleSignInLauncher.launch(signInIntent)
        }
    }

    private fun onAuthSuccess(name: String, email: String, photoUrl: String, idToken: String?) {
        val cleanName = if (name.isNotBlank() && name != "Jarvis User") name else email.substringBefore("@", "Jarvis User")

        if (email.isBlank()) {
            showError("Google did not return an email address.")
            return
        }
        if (idToken.isNullOrBlank()) {
            showError("Google did not return an ID token. Please try again.")
            return
        }

        val credential = GoogleAuthProvider.getCredential(idToken, null)
        signInProgressBar.visibility = View.VISIBLE
        googleSignInBtn.isEnabled = false
        firebaseAuth.signInWithCredential(credential).addOnCompleteListener { task ->
            if (!task.isSuccessful) {
                Log.e("LoginActivity", "Firebase Google auth failed", task.exception)
                showError(task.exception?.message?.let { "Firebase sign-in failed: $it" } ?: "Firebase sign-in failed. Please try again.")
                return@addOnCompleteListener
            }
            val user = firebaseAuth.currentUser
            if (user == null) {
                showError("Firebase sign-in completed without a user. Please try again.")
                return@addOnCompleteListener
            }
            proceedWithUser(user.uid, user.displayName?.takeIf { it.isNotBlank() } ?: cleanName, user.email?.takeIf { it.isNotBlank() } ?: email, user.photoUrl?.toString() ?: photoUrl)
        }
    }
    private fun authenticateWithFirebaseUser(name: String, email: String, photoUrl: String) {
        val validEmail = if (email.contains("@")) email else "user_${System.currentTimeMillis()}@gmail.com"
        val tempPassword = "JarvisUser#2026!Secured"

        firebaseAuth.signInWithEmailAndPassword(validEmail, tempPassword)
            .addOnCompleteListener { task ->
                if (task.isSuccessful && firebaseAuth.currentUser != null) {
                    val user = firebaseAuth.currentUser!!
                    proceedWithUser(user.uid, name, validEmail, photoUrl)
                } else {
                    firebaseAuth.createUserWithEmailAndPassword(validEmail, tempPassword)
                        .addOnCompleteListener { createTask ->
                            if (createTask.isSuccessful && firebaseAuth.currentUser != null) {
                                val user = firebaseAuth.currentUser!!
                                proceedWithUser(user.uid, name, validEmail, photoUrl)
                            } else {
                                Log.w("LoginActivity", "Email auth fallback (${createTask.exception?.message}). Trying Anonymous Auth.")
                                firebaseAuth.signInAnonymously()
                                    .addOnCompleteListener { anonTask ->
                                        if (anonTask.isSuccessful && firebaseAuth.currentUser != null) {
                                            val anonUser = firebaseAuth.currentUser!!
                                            proceedWithUser(anonUser.uid, name, validEmail, photoUrl)
                                        } else {
                                            val cleanEmailStr = validEmail.lowercase().replace(Regex("[^a-z0-9]"), "")
                                            val clientUid = "usr_" + cleanEmailStr
                                            proceedWithUser(clientUid, name, validEmail, photoUrl)
                                        }
                                    }
                            }
                        }
                }
            }
    }

    private fun proceedWithUser(uid: String, name: String, email: String, photoUrl: String) {
        signInProgressBar.visibility = View.VISIBLE
        googleSignInBtn.isEnabled = false

        com.jarvis.assistant.firebase.UserFirestoreHelper.fetchProfile(uid, email) { cloudProfile ->
            val finalName = cloudProfile?.name?.takeIf { it.isNotBlank() && it != "Jarvis User" } ?: name
            val finalPhone = cloudProfile?.phone?.takeIf { it.isNotBlank() } ?: ""
            val finalPhoto = cloudProfile?.photoUrl?.takeIf { it.isNotBlank() } ?: photoUrl
            val hasCompleteProfile = finalPhone.isNotBlank() && finalName.isNotBlank()

            val prefs = getSharedPreferences("jarvis_prefs", MODE_PRIVATE)

            if (cloudProfile != null && hasCompleteProfile) {
                com.jarvis.assistant.firebase.UserFirestoreHelper.syncToLocalPrefs(this@LoginActivity, cloudProfile)
                com.jarvis.assistant.firebase.UserFirestoreHelper.updateLastLogin(cloudProfile.uid.ifBlank { uid })

                signInProgressBar.visibility = View.GONE
                Toast.makeText(this@LoginActivity, "Welcome back to JARVIS, $finalName!", Toast.LENGTH_SHORT).show()
                launchMainActivity()
            } else {
                prefs.edit()
                    .putBoolean("is_authenticated", true)
                    .putString("user_uid", uid)
                    .putString("user_name", finalName)
                    .putString("user_email", email)
                    .putString("user_photo", finalPhoto)
                    .putString("user_phone", finalPhone)
                    .putBoolean("is_profile_complete", hasCompleteProfile)
                    .putLong("accepted_terms_timestamp", System.currentTimeMillis())
                    .apply()

                signInProgressBar.visibility = View.GONE
                Toast.makeText(this@LoginActivity, "Welcome to JARVIS, $finalName!", Toast.LENGTH_SHORT).show()

                if (hasCompleteProfile) {
                    launchMainActivity()
                } else {
                    launchProfileSetup()
                }
            }
        }
    }

    private fun isLocallyAuthenticated(): Boolean {
        return getSharedPreferences("jarvis_prefs", MODE_PRIVATE).getBoolean("is_authenticated", false)
    }

    private fun showError(msg: String) {
        signInProgressBar.visibility = View.GONE
        googleSignInBtn.isEnabled = termsCheckBox.isChecked
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }

    private fun launchMainActivity() {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        startActivity(intent)
        finish()
    }

    private fun launchProfileSetup() {
        val intent = Intent(this, ProfileSetupActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        startActivity(intent)
        finish()
    }

    private fun launchNextScreen() {
        val prefs = getSharedPreferences("jarvis_prefs", MODE_PRIVATE)
        val isProfileComplete = prefs.getBoolean("is_profile_complete", false)
        if (isProfileComplete) launchMainActivity() else launchProfileSetup()
    }

    override fun onResume() {
        super.onResume()
        loginOrbView.onResume()
        // Keep the floating JARVIS orb available as soon as overlay permission is granted,
        // including while the user is on the login screen.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && Settings.canDrawOverlays(this)) {
            FloatingOrbService.startService(this)
        }
    }

    override fun onPause() {
        super.onPause()
        loginOrbView.onPause()
    }
}
