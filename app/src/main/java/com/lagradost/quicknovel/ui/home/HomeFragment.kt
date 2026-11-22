package com.lagradost.quicknovel.ui.home

import androidx.fragment.app.viewModels
import com.lagradost.cloudstream4.BaseComposeFragment
import com.lagradost.cloudstream4.compose.Screen

class HomeFragment : BaseComposeFragment(), Screen by HomeScreen {
    private val viewModel: HomeViewModel by viewModels()

    override fun onResume() {
        super.onResume()
        viewModel.refreshApis()
    }
}