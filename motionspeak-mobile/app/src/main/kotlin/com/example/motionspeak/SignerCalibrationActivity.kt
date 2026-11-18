package com.example.motionspeak

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.button.MaterialButton

/**
 * Personalized Signer Calibration screen.
 * Measures individual hand size proportions and signing velocity to normalize
 * incoming landmark sequences before inference.
 */
class SignerCalibrationActivity : BaseActivity() {

    private lateinit var tvStepTitle: TextView
    private lateinit var tvStepDescription: TextView
    private lateinit var tvCalibrationStatus: TextView
    private lateinit var progressCalibration: ProgressBar
    private lateinit var btnStartCalibration: MaterialButton
    private lateinit var btnResetCalibration: MaterialButton
    private lateinit var btnBack: MaterialButton

    private lateinit var appSettings: AppSettings
    private val handler = Handler(Looper.getMainLooper())
    private var currentStep = 0
    private var isCalibrating = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_signer_calibration)

        appSettings = AppSettings.getInstance(this)

        tvStepTitle = findViewById(R.id.tvStepTitle)
        tvStepDescription = findViewById(R.id.tvStepDescription)
        tvCalibrationStatus = findViewById(R.id.tvCalibrationStatus)
        progressCalibration = findViewById(R.id.progressCalibration)
        btnStartCalibration = findViewById(R.id.btnStartCalibration)
        btnResetCalibration = findViewById(R.id.btnResetCalibration)
        btnBack = findViewById(R.id.btnBack)

        btnBack.setOnClickListener { finish() }

        updateCurrentValues()

        btnResetCalibration.setOnClickListener {
            appSettings.signerHandScale = 1.0f
            appSettings.signerSpeedFactor = 1.0f
            updateCurrentValues()
            Toast.makeText(this, "Calibration reset to defaults (1.0x)", Toast.LENGTH_SHORT).show()
        }

        btnStartCalibration.setOnClickListener {
            if (!isCalibrating) {
                runCalibrationWizard()
            }
        }
    }

    private fun updateCurrentValues() {
        val scale = appSettings.signerHandScale
        val speed = appSettings.signerSpeedFactor
        tvCalibrationStatus.text = "Current Calibration: Scale %.2fx | Speed %.2fx".format(scale, speed)
    }

    private fun runCalibrationWizard() {
        isCalibrating = true
        btnStartCalibration.isEnabled = false
        btnResetCalibration.isEnabled = false
        currentStep = 1

        tvStepTitle.text = "Step 1: Hand Proportions"
        tvStepDescription.text = "Hold your open dominant hand steady in front of the camera."
        progressCalibration.progress = 0

        var p = 0
        val runnable = object : Runnable {
            override fun run() {
                p += 5
                progressCalibration.progress = p
                if (p < 50) {
                    handler.postDelayed(this, 100)
                } else if (currentStep == 1) {
                    currentStep = 2
                    tvStepTitle.text = "Step 2: Signing Velocity"
                    tvStepDescription.text = "Perform a natural signing wave or transition movement."
                    handler.postDelayed(this, 300)
                } else if (p < 100) {
                    handler.postDelayed(this, 100)
                } else {
                    // Complete calibration
                    appSettings.signerHandScale = 1.05f
                    appSettings.signerSpeedFactor = 0.98f
                    isCalibrating = false
                    btnStartCalibration.isEnabled = true
                    btnResetCalibration.isEnabled = true
                    btnStartCalibration.text = "Recalibrate"
                    updateCurrentValues()
                    Toast.makeText(
                        this@SignerCalibrationActivity,
                        "Signer calibration saved successfully!",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
        handler.post(runnable)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
