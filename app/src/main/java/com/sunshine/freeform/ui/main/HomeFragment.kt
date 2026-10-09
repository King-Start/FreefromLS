package com.sunshine.freeform.ui.main

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.content.res.AppCompatResources
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.sunshine.freeform.R
import com.sunshine.freeform.app.MiFreeform
import com.sunshine.freeform.databinding.FragmentHomeBinding
import com.sunshine.freeform.service.KeepAliveService
import com.sunshine.freeform.ui.guide.GuideActivity
import com.sunshine.freeform.utils.PermissionUtils
import com.sunshine.freeform.utils.BackendSelector
import com.sunshine.freeform.utils.StandaloneFreeform
import com.sunshine.freeform.utils.XposedCapability
import rikka.sui.Sui

class HomeFragment : Fragment(), View.OnClickListener {

    private var _binding: FragmentHomeBinding? = null

    private val binding get() = _binding!!

    private lateinit var accessibilityRFAR: ActivityResultLauncher<Intent>
    private lateinit var sp: SharedPreferences

    companion object {
        private const val TAG = "HomeFragment"
        private const val COMMON_QUESTION_ZH = "https://github.com/sunshine0523/Mi-FreeForm/blob/master/qa_zh-Hans.md"
        private const val OPEN_API_ZH = "https://github.com/sunshine0523/Mi-FreeForm/blob/master/open_api_zh-Hans.md"
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root

    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        sp = requireContext().getSharedPreferences(MiFreeform.APP_SETTINGS_NAME, Context.MODE_PRIVATE)
        checkShizukuPermission()
        checkAccessibilityPermission()
        accessibilityRFAR = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            checkAccessibilityPermission()
        }

        binding.materialCardViewShizukuInfo.setOnClickListener(this)
        binding.materialCardViewAccessibilityInfo.setOnClickListener(this)
        binding.buttonGuide.setOnClickListener(this)
        binding.buttonQuestion.setOnClickListener(this)
        binding.buttonOpenApi.setOnClickListener(this)
    }

    override fun onResume() {
        super.onResume()

        checkAccessibilityPermission()
    }

    private fun checkAccessibilityPermission() {
        val result = PermissionUtils.isAccessibilitySettingsOn(requireContext())

        when (sp.getInt("service_type", KeepAliveService.SERVICE_TYPE)) {
            KeepAliveService.SERVICE_TYPE -> {
                if (!result) {
                    binding.materialCardViewAccessibilityInfo.visibility = View.VISIBLE
                    binding.infoAccessibilityBg.setBackgroundColor(resources.getColor(R.color.warn_color))
                    binding.imageViewAccessibilityService.setImageDrawable(AppCompatResources.getDrawable(requireContext(), R.drawable.ic_error_white))
                    binding.textViewAccessibilityServiceInfo.text = getString(R.string.accessibility_no_start)
                } else {
                    binding.materialCardViewAccessibilityInfo.visibility = View.GONE
                }
            }
            else -> {
                binding.materialCardViewAccessibilityInfo.visibility = View.GONE
            }
        }
    }

    private fun checkShizukuPermission(): Boolean {
        val shizuku = BackendSelector.allowsShizuku() && MiFreeform.me?.isRunning?.value == true
        val xposed = BackendSelector.allowsXposed() && XposedCapability.isAvailable()
        val rom = BackendSelector.allowsRomFreeform() && StandaloneFreeform.isSupported(requireContext())
        val result = shizuku || xposed || rom
        if (result) {
            binding.infoShizukuBg.setBackgroundColor(resources.getColor(R.color.success_color))
            binding.imageViewShizukuService.setImageDrawable(AppCompatResources.getDrawable(requireContext(), R.drawable.ic_done))
            binding.textViewShizukuServiceInfo.text = when {
                shizuku -> if (Sui.isSui()) getString(R.string.sui_start_short) else getString(R.string.shizuku_start_short)
                xposed -> getString(R.string.xposed_start_short)
                rom -> getString(R.string.standalone_rom_freeform)
                else -> getString(R.string.standalone_mode)
            }
        } else {
            binding.infoShizukuBg.setBackgroundColor(resources.getColor(R.color.warn_color))
            binding.imageViewShizukuService.setImageDrawable(AppCompatResources.getDrawable(requireContext(), R.drawable.ic_error_white))
            binding.textViewShizukuServiceInfo.text = getString(R.string.standalone_mode)
        }
        return result
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    override fun onClick(v: View) {
        when(v.id) {
            R.id.materialCardView_shizuku_info -> {
                MiFreeform.me?.initShizuku()
                if (checkShizukuPermission()) {
                    Snackbar.make(binding.root, getString(R.string.shizuku_start), Snackbar.LENGTH_SHORT).show()
                } else {
                    Snackbar.make(binding.root, getString(R.string.try_to_init_shizuku), Snackbar.LENGTH_SHORT).show()
                }

            }

            R.id.materialCardView_accessibility_info -> {
                MaterialAlertDialogBuilder(requireContext()).apply {
                    setTitle(getString(R.string.warn))
                    setMessage(getString(R.string.home_accessibility_warn_message))
                    setPositiveButton(getString(R.string.go_to_start_accessibility)) { _, _ ->
                        accessibilityRFAR.launch(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    }
                    setNegativeButton(getString(R.string.go_to_change_service_type)) { _, _ ->
                        (requireActivity() as MainActivity).changeToSetting()
                    }
                    create().show()
                }
            }

            R.id.button_guide -> {
                startActivity(Intent(requireContext(), GuideActivity::class.java))
            }

            R.id.button_question -> {
                val uri = Uri.parse(COMMON_QUESTION_ZH)
                val intent = Intent(Intent.ACTION_VIEW, uri)
                startActivity(intent)
            }

            R.id.button_open_api -> {
                val uri = Uri.parse(OPEN_API_ZH)
                val intent = Intent(Intent.ACTION_VIEW, uri)
                startActivity(intent)
            }
        }
    }
}