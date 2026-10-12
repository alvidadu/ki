package com.sms.stshoppay

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.google.android.material.button.MaterialButton

class DeveloperFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_developer, container, false)

        view.findViewById<MaterialButton>(R.id.btnDevShawon).setOnClickListener {
            openUrl("https://t.me/Shawon_Bhai_121")
        }

        view.findViewById<MaterialButton>(R.id.btnStChannel).setOnClickListener {
            openUrl("https://t.me/ST_Technical_Creator")
        }

        return view
    }

    private fun openUrl(url: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "টেলিগ্রাম ওপেন করা সম্ভব হয়নি", Toast.LENGTH_SHORT).show()
        }
    }
}
