package com.mtgscanbuild.ui

import androidx.compose.runtime.Composable

@Composable
fun AdvertisingProvider(hasPro: Boolean, content: @Composable () -> Unit) = content()

@Composable
fun BasicBanner(route: String?, hasPro: Boolean) = Unit

@Composable
fun AdvertisingPrivacyOptions() = Unit
