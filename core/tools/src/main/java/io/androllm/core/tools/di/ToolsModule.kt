package io.androllm.core.tools.di

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import io.androllm.core.tools.api.Tool
import io.androllm.core.tools.registry.ToolRegistry
import io.androllm.core.tools.tool.impl.AlarmTool
import io.androllm.core.tools.tool.impl.AppLauncherTool
import io.androllm.core.tools.tool.impl.BatteryTool
import io.androllm.core.tools.tool.impl.BluetoothTool
import io.androllm.core.tools.tool.impl.CalculatorTool
import io.androllm.core.tools.tool.impl.CalendarTool
import io.androllm.core.tools.tool.impl.CameraTool
import io.androllm.core.tools.tool.impl.ClipboardTool
import io.androllm.core.tools.tool.impl.ContactTool
import io.androllm.core.tools.tool.impl.CurrencyTool
import io.androllm.core.tools.tool.impl.DeviceInfoTool
import io.androllm.core.tools.tool.impl.EmailTool
import io.androllm.core.tools.tool.impl.FlashlightTool
import io.androllm.core.tools.tool.impl.GalleryTool
import io.androllm.core.tools.tool.impl.GitHubTool
import io.androllm.core.tools.tool.impl.ListAppFilesTool
import io.androllm.core.tools.tool.impl.ListDownloadsTool
import io.androllm.core.tools.tool.impl.MapsSearchTool
import io.androllm.core.tools.tool.impl.MapsTool
import io.androllm.core.tools.tool.impl.MarkdownExportTool
import io.androllm.core.tools.tool.impl.MusicTool
import io.androllm.core.tools.tool.impl.NoteDeleteTool
import io.androllm.core.tools.tool.impl.NoteGetTool
import io.androllm.core.tools.tool.impl.NoteListTool
import io.androllm.core.tools.tool.impl.NoteSaveTool
import io.androllm.core.tools.tool.impl.NotificationTool
import io.androllm.core.tools.tool.impl.PackageManagerTool
import io.androllm.core.tools.tool.impl.PdfExportTool
import io.androllm.core.tools.tool.impl.PhoneTool
import io.androllm.core.tools.tool.impl.ReminderTool
import io.androllm.core.tools.tool.impl.RunningAppsTool
import io.androllm.core.tools.tool.impl.ScreenshotTool
import io.androllm.core.tools.tool.impl.ShareTool
import io.androllm.core.tools.tool.impl.SmsTool
import io.androllm.core.tools.tool.impl.TranslationTool
import io.androllm.core.tools.tool.impl.UnitConverterTool
import io.androllm.core.tools.tool.impl.VariableGetTool
import io.androllm.core.tools.tool.impl.VariableSetTool
import io.androllm.core.tools.tool.impl.VoiceRecorderTool
import io.androllm.core.tools.tool.impl.VolumeTool
import io.androllm.core.tools.tool.impl.WeatherTool
import io.androllm.core.tools.tool.impl.WebSearchTool
import io.androllm.core.tools.tool.impl.WifiTool
import javax.inject.Singleton

/**
 * Registers every built-in tool into the multibinding `Set<Tool>` that
 * populates [ToolRegistry]. New tools — including third-party plugins — are
 * added the same way: implement [Tool], add a `@Binds @IntoSet` entry here or
 * in the plugin's own Hilt module, and the planner sees it automatically.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class ToolsModule {

    @Binds @IntoSet abstract fun bindWeather(tool: WeatherTool): Tool
    @Binds @IntoSet abstract fun bindWebSearch(tool: WebSearchTool): Tool
    @Binds @IntoSet abstract fun bindSms(tool: SmsTool): Tool
    @Binds @IntoSet abstract fun bindPhone(tool: PhoneTool): Tool
    @Binds @IntoSet abstract fun bindEmail(tool: EmailTool): Tool
    @Binds @IntoSet abstract fun bindMaps(tool: MapsTool): Tool
    @Binds @IntoSet abstract fun bindMapsSearch(tool: MapsSearchTool): Tool
    @Binds @IntoSet abstract fun bindCalendar(tool: CalendarTool): Tool
    @Binds @IntoSet abstract fun bindReminder(tool: ReminderTool): Tool
    @Binds @IntoSet abstract fun bindAlarm(tool: AlarmTool): Tool
    @Binds @IntoSet abstract fun bindClipboard(tool: ClipboardTool): Tool
    @Binds @IntoSet abstract fun bindNotifications(tool: NotificationTool): Tool
    @Binds @IntoSet abstract fun bindFlashlight(tool: FlashlightTool): Tool
    @Binds @IntoSet abstract fun bindBluetooth(tool: BluetoothTool): Tool
    @Binds @IntoSet abstract fun bindWifi(tool: WifiTool): Tool
    @Binds @IntoSet abstract fun bindMusic(tool: MusicTool): Tool
    @Binds @IntoSet abstract fun bindAppLauncher(tool: AppLauncherTool): Tool
    @Binds @IntoSet abstract fun bindContacts(tool: ContactTool): Tool
    @Binds @IntoSet abstract fun bindCamera(tool: CameraTool): Tool
    @Binds @IntoSet abstract fun bindScreenshot(tool: ScreenshotTool): Tool
    @Binds @IntoSet abstract fun bindShare(tool: ShareTool): Tool

    // Workflow engine: model-visible variable read/write (loops, chaining)
    @Binds @IntoSet abstract fun bindVariableSet(tool: VariableSetTool): Tool
    @Binds @IntoSet abstract fun bindVariableGet(tool: VariableGetTool): Tool

    // Device & system
    @Binds @IntoSet abstract fun bindBattery(tool: BatteryTool): Tool
    @Binds @IntoSet abstract fun bindVolume(tool: VolumeTool): Tool
    @Binds @IntoSet abstract fun bindDeviceInfo(tool: DeviceInfoTool): Tool
    @Binds @IntoSet abstract fun bindListApps(tool: PackageManagerTool): Tool
    @Binds @IntoSet abstract fun bindRunningApps(tool: RunningAppsTool): Tool
    @Binds @IntoSet abstract fun bindGallery(tool: GalleryTool): Tool

    // Compute & information
    @Binds @IntoSet abstract fun bindCalculator(tool: CalculatorTool): Tool
    @Binds @IntoSet abstract fun bindUnitConverter(tool: UnitConverterTool): Tool
    @Binds @IntoSet abstract fun bindCurrency(tool: CurrencyTool): Tool
    @Binds @IntoSet abstract fun bindTranslation(tool: TranslationTool): Tool
    @Binds @IntoSet abstract fun bindGitHub(tool: GitHubTool): Tool

    // Productivity
    @Binds @IntoSet abstract fun bindNoteSave(tool: NoteSaveTool): Tool
    @Binds @IntoSet abstract fun bindNoteList(tool: NoteListTool): Tool
    @Binds @IntoSet abstract fun bindNoteGet(tool: NoteGetTool): Tool
    @Binds @IntoSet abstract fun bindNoteDelete(tool: NoteDeleteTool): Tool
    @Binds @IntoSet abstract fun bindListDownloads(tool: ListDownloadsTool): Tool
    @Binds @IntoSet abstract fun bindListAppFiles(tool: ListAppFilesTool): Tool
    @Binds @IntoSet abstract fun bindPdfExport(tool: PdfExportTool): Tool
    @Binds @IntoSet abstract fun bindMarkdownExport(tool: MarkdownExportTool): Tool
    @Binds @IntoSet abstract fun bindVoiceRecorder(tool: VoiceRecorderTool): Tool

    companion object {

        @Provides
        @Singleton
        fun provideToolRegistry(tools: @JvmSuppressWildcards Set<Tool>): ToolRegistry =
            ToolRegistry().also { it.registerAll(tools) }
    }
}
