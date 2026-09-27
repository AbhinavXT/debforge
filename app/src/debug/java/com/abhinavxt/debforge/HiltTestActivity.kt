package com.abhinavxt.debforge

import androidx.activity.ComponentActivity
import dagger.hilt.android.AndroidEntryPoint

/** Host for screenshot tests: a blank activity Hilt can inject view-models into. Debug builds only. */
@AndroidEntryPoint
class HiltTestActivity : ComponentActivity()
