package io.androllm.core.accessibility.di

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import io.androllm.core.accessibility.ocr.NoopOcrEngine
import io.androllm.core.accessibility.ocr.OcrEngine
import io.androllm.core.accessibility.tools.UiClickTool
import io.androllm.core.accessibility.tools.UiNavigateTool
import io.androllm.core.accessibility.tools.QrScanTool
import io.androllm.core.accessibility.tools.UiGestureTool
import io.androllm.core.accessibility.tools.UiReadScreenTool
import io.androllm.core.accessibility.tools.UiRunTaskTool
import io.androllm.core.accessibility.tools.UiScrollTool
import io.androllm.core.accessibility.tools.UiSwipeTool
import io.androllm.core.accessibility.tools.UiTypeTool
import io.androllm.core.tools.api.Tool
import javax.inject.Singleton

/**
 * Registers the accessibility `ui_*` tools into the same `Set<Tool>`
 * multibinding that populates [io.androllm.core.tools.registry.ToolRegistry]
 * — the planner sees them automatically, exactly like every other tool, and
 * they appear in Settings → Automation with their own permission toggle.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class AccessibilityToolsModule {

    @Binds @IntoSet abstract fun bindUiReadScreen(tool: UiReadScreenTool): Tool
    @Binds @IntoSet abstract fun bindUiClick(tool: UiClickTool): Tool
    @Binds @IntoSet abstract fun bindUiType(tool: UiTypeTool): Tool
    @Binds @IntoSet abstract fun bindUiScroll(tool: UiScrollTool): Tool
    @Binds @IntoSet abstract fun bindUiSwipe(tool: UiSwipeTool): Tool
    @Binds @IntoSet abstract fun bindUiGesture(tool: UiGestureTool): Tool
    @Binds @IntoSet abstract fun bindUiNavigate(tool: UiNavigateTool): Tool
    @Binds @IntoSet abstract fun bindUiRun(tool: UiRunTaskTool): Tool
    @Binds @IntoSet abstract fun bindQrScan(tool: QrScanTool): Tool

    companion object {
        /**
         * The OCR engine for the vision fallback. The pluggable interface is
         * bound to the no-op implementation until a real on-device model ships
         * — the screen analyzer simply skips OCR then.
         */
        @Provides
        @Singleton
        fun provideOcrEngine(): OcrEngine = NoopOcrEngine
    }
}
