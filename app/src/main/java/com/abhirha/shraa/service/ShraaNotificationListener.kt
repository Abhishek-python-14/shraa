package com.abhirha.shraa.service

import android.service.notification.NotificationListenerService

/**
 * Intentionally empty. Enabling this listener is what lets Android hand SHRAA the active media
 * sessions (see MediaAccess). SHRAA never reads or stores notification content.
 */
class ShraaNotificationListener : NotificationListenerService()
