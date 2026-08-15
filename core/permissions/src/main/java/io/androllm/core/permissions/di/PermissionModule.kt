package io.androllm.core.permissions.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import io.androllm.core.permissions.PermissionHandler
import io.androllm.core.permissions.handler.AccessibilityAccessHandler
import io.androllm.core.permissions.handler.AlarmAccessHandler
import io.androllm.core.permissions.handler.BluetoothPermissionHandler
import io.androllm.core.permissions.handler.CalendarPermissionHandler
import io.androllm.core.permissions.handler.CameraPermissionHandler
import io.androllm.core.permissions.handler.ContactsPermissionHandler
import io.androllm.core.permissions.handler.LocationPermissionHandler
import io.androllm.core.permissions.handler.MicrophonePermissionHandler
import io.androllm.core.permissions.handler.NotificationPermissionHandler
import io.androllm.core.permissions.handler.SmsPermissionHandler

/**
 * Registers every permission/access gate into the central
 * [io.androllm.core.permissions.PermissionManager] via Hilt multibinding.
 * Adding a new permission = adding a handler + one @Binds line here; the
 * setup screen and Settings → Permissions & Access pick it up automatically.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class PermissionModule {

    @Binds @IntoSet abstract fun bindMicrophone(handler: MicrophonePermissionHandler): PermissionHandler
    @Binds @IntoSet abstract fun bindNotifications(handler: NotificationPermissionHandler): PermissionHandler
    @Binds @IntoSet abstract fun bindAccessibility(handler: AccessibilityAccessHandler): PermissionHandler
    @Binds @IntoSet abstract fun bindContacts(handler: ContactsPermissionHandler): PermissionHandler
    @Binds @IntoSet abstract fun bindSms(handler: SmsPermissionHandler): PermissionHandler
    @Binds @IntoSet abstract fun bindCalendar(handler: CalendarPermissionHandler): PermissionHandler
    @Binds @IntoSet abstract fun bindCamera(handler: CameraPermissionHandler): PermissionHandler
    @Binds @IntoSet abstract fun bindLocation(handler: LocationPermissionHandler): PermissionHandler
    @Binds @IntoSet abstract fun bindBluetooth(handler: BluetoothPermissionHandler): PermissionHandler
    @Binds @IntoSet abstract fun bindAlarms(handler: AlarmAccessHandler): PermissionHandler
}
