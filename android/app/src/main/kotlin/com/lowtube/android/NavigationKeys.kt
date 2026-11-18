package com.lowtube.android

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

@Serializable data object Main : NavKey
@Serializable data object Settings : NavKey
@Serializable data object Library : NavKey
@Serializable data object Queue : NavKey
@Serializable data object Downloads : NavKey
@Serializable data object Player : NavKey
