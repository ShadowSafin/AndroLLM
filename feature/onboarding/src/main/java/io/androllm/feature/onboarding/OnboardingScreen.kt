package io.androllm.feature.onboarding

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import io.androllm.core.ui.components.CloudAtmosphericBackground
import io.androllm.core.ui.components.CloudCapsuleButton
import io.androllm.core.ui.components.CloudChip
import io.androllm.core.ui.components.rememberReduceMotion
import io.androllm.core.ui.theme.DeskInk
import io.androllm.core.ui.theme.DeskInkFaint
import io.androllm.core.ui.theme.DeskPaper
import io.androllm.core.ui.theme.LampAmber
import io.androllm.core.ui.theme.LampDeep
import io.androllm.core.ui.theme.LampGlow
import kotlin.math.abs
import io.androllm.core.ui.theme.ledger

/**
 * The five-page Writer's Night Desk introduction.
 *
 * Flow: Skip / Get Started both persist the completion flag and call
 * [onFinished], after which the host decides where to continue.
 */
@Composable
fun OnboardingScreen(
    onFinished: () -> Unit,
    viewModel: OnboardingViewModel = hiltViewModel()
) {
    val currentPage by viewModel.currentPage.collectAsState()
    val reduceMotion = rememberReduceMotion()
    val pagerState = rememberPagerState(initialPage = 0) { viewModel.pageCount }

    // Pager swipes -> ViewModel (single source of truth for dots & the CTA).
    androidx.compose.runtime.LaunchedEffect(pagerState.currentPage) {
        viewModel.setPage(pagerState.currentPage)
    }
    // ViewModel changes (e.g. Next button) -> pager.
    androidx.compose.runtime.LaunchedEffect(currentPage) {
        if (pagerState.currentPage != currentPage) {
            pagerState.animateScrollToPage(currentPage)
        }
    }

    CloudAtmosphericBackground {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
        ) {
            // Skip affordance
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (!viewModel.isLastPage) {
                    TextButton(onClick = { viewModel.complete(onFinished) }) {
                        Text(
                            text = stringResource(R.string.onboarding_skip),
                            color = MaterialTheme.ledger.deskInk
                        )
                    }
                }
            }

            // Pages
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.weight(1f),
                beyondViewportPageCount = 0
            ) { page ->
                val pageOffset = ((pagerState.currentPage - page) + pagerState.currentPageOffsetFraction)
                OnboardingPage(
                    page = page,
                    offset = pageOffset,
                    reduceMotion = reduceMotion
                )
            }

            // Progress dots + primary action
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 20.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                OnboardingDots(
                    total = viewModel.pageCount,
                    current = currentPage
                )
                CloudCapsuleButton(
                    text = stringResource(
                        if (viewModel.isLastPage) R.string.onboarding_get_started else R.string.onboarding_next
                    ),
                    onClick = {
                        if (viewModel.isLastPage) {
                            viewModel.complete(onFinished)
                        } else {
                            viewModel.next()
                        }
                    },
                    gradient = androidx.compose.ui.graphics.Brush.horizontalGradient(
                        listOf(MaterialTheme.ledger.lampAmber, MaterialTheme.ledger.lampGlow)
                    ),
                    modifier = Modifier.width(150.dp)
                )
            }
        }
    }
}

/**
 * A single onboarding page: animated illustration, headline, subtitle and
 * subtle feature chips, with a gentle parallax driven by the pager offset.
 */
@Composable
private fun OnboardingPage(
    page: Int,
    offset: Float,
    reduceMotion: Boolean
) {
    val title = stringResource(PAGES[page].titleRes)
    val subtitle = stringResource(PAGES[page].subtitleRes)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer {
                val clamped = abs(offset).coerceIn(0f, 1f)
                alpha = 1f - clamped * 0.35f
                translationX = offset * 56f * density
            }
            .padding(horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(250.dp),
            contentAlignment = Alignment.Center
        ) {
            when (page) {
                0 -> WelcomeScene(reduceMotion = reduceMotion)
                1 -> LocalScene(reduceMotion = reduceMotion)
                2 -> LightningScene(reduceMotion = reduceMotion)
                3 -> ModelsScene(reduceMotion = reduceMotion)
                else -> ReadyScene(reduceMotion = reduceMotion)
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        Text(
            text = title,
            style = MaterialTheme.typography.headlineLarge.copy(
                fontWeight = FontWeight.ExtraBold,
                color = MaterialTheme.ledger.deskPaper
            ),
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodyLarge.copy(
                color = MaterialTheme.ledger.deskInk,
                lineHeight = MaterialTheme.typography.bodyLarge.lineHeight * 1.25f
            ),
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(24.dp))

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            PAGES[page].chips.forEach { chip ->
                CloudChip(text = chip)
            }
        }
    }
}

/**
 * Animated pill-style progress indicator.
 */
@Composable
private fun OnboardingDots(
    total: Int,
    current: Int
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        repeat(total) { index ->
            val active = index == current
            val width by animateDpAsState(
                targetValue = if (active) 30.dp else 8.dp,
                animationSpec = spring(),
                label = "dotWidth"
            )
            Box(
                modifier = Modifier
                    .height(8.dp)
                    .width(width)
                    .clip(CircleShape)
                    .background(
                        if (active) MaterialTheme.ledger.lampDeep else MaterialTheme.ledger.deskInkFaint.copy(alpha = 0.4f)
                    )
            )
        }
    }
}

private data class OnboardingPageData(
    val titleRes: Int,
    val subtitleRes: Int,
    val chips: List<String>
)

private val PAGES = listOf(
    OnboardingPageData(
        titleRes = R.string.onboarding_page1_title,
        subtitleRes = R.string.onboarding_page1_subtitle,
        chips = listOf("On-Device", "Private", "Free")
    ),
    OnboardingPageData(
        titleRes = R.string.onboarding_page2_title,
        subtitleRes = R.string.onboarding_page2_subtitle,
        chips = listOf("No Cloud", "Offline", "Private by Design")
    ),
    OnboardingPageData(
        titleRes = R.string.onboarding_page3_title,
        subtitleRes = R.string.onboarding_page3_subtitle,
        chips = listOf("GGUF Optimized", "Vulkan GPU", "Streaming")
    ),
    OnboardingPageData(
        titleRes = R.string.onboarding_page4_title,
        subtitleRes = R.string.onboarding_page4_subtitle,
        chips = listOf("Download", "Switch Instantly", "Quantized")
    ),
    OnboardingPageData(
        titleRes = R.string.onboarding_page5_title,
        subtitleRes = R.string.onboarding_page5_subtitle,
        chips = emptyList()
    )
)
