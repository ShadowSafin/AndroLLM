package io.androllm.feature.onboarding

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import io.androllm.core.ui.components.AuroraBackground
import io.androllm.core.ui.components.AuroraBlue
import io.androllm.core.ui.components.AuroraCapsuleButton
import io.androllm.core.ui.components.AuroraCyan
import io.androllm.core.ui.components.AuroraEmerald
import io.androllm.core.ui.components.AuroraMagenta
import io.androllm.core.ui.components.AuroraProgressBar
import io.androllm.core.ui.components.AuroraViolet
import io.androllm.core.ui.components.GlassChip
import io.androllm.core.ui.components.StaggeredEntrance
import io.androllm.core.ui.components.rememberReduceMotion
import io.androllm.core.ui.theme.ledger
import kotlin.math.abs

/** Per-page accent pair: (orb high, orb low). */
private val PAGE_ACCENTS = listOf(
    AuroraViolet to AuroraCyan,
    AuroraEmerald to AuroraViolet,
    AuroraCyan to AuroraBlue,
    AuroraBlue to AuroraMagenta,
    AuroraMagenta to AuroraViolet
)
private val PAGE_CHIP_ACCENTS = listOf(
    AuroraViolet, AuroraEmerald, AuroraCyan, AuroraBlue, AuroraMagenta
)

/**
 * The five-page Aurora introduction — neon light on the blackout ground.
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

    // Pager swipes -> ViewModel (single source of truth for progress & CTA).
    LaunchedEffect(pagerState.currentPage) {
        viewModel.setPage(pagerState.currentPage)
    }
    // ViewModel changes (e.g. Next button) -> pager.
    LaunchedEffect(currentPage) {
        if (pagerState.currentPage != currentPage) {
            pagerState.animateScrollToPage(currentPage)
        }
    }

    val accentA by animateColorAsState(
        targetValue = PAGE_ACCENTS[currentPage].first,
        animationSpec = tween(700),
        label = "accentA"
    )
    val accentB by animateColorAsState(
        targetValue = PAGE_ACCENTS[currentPage].second,
        animationSpec = tween(700),
        label = "accentB"
    )

    AuroraBackground(
        accentA = accentA,
        accentB = accentB,
        reduceMotion = reduceMotion
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp)
        ) {
            // Top bar — back on the left (page > 0), skip on the right.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (currentPage > 0) {
                    IconButton(onClick = { viewModel.back() }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.onboarding_back),
                            tint = MaterialTheme.ledger.deskPaperDim
                        )
                    }
                } else {
                    Spacer(modifier = Modifier.height(1.dp))
                }
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

            // Progress track
            AuroraProgressBar(
                progress = (currentPage + 1).toFloat() / viewModel.pageCount,
                brush = Brush.horizontalGradient(listOf(accentA, accentB)),
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Primary action
            AuroraCapsuleButton(
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
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

/**
 * A single onboarding page: animated scene, huge headline, subtitle and
 * glass feature chips, with parallax + rotation driven by the pager offset.
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
                alpha = 1f - clamped * 0.5f
                translationX = offset * 80f * density
                rotationZ = offset * 3f
                val s = 1f - clamped * 0.08f
                scaleX = s
                scaleY = s
            },
        horizontalAlignment = Alignment.Start
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(300.dp),
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

        Spacer(modifier = Modifier.height(8.dp))

        StaggeredEntrance(index = 1) {
            Text(
                text = title,
                style = MaterialTheme.typography.displayMedium.copy(
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.ledger.deskPaper
                )
            )
        }

        Spacer(modifier = Modifier.height(14.dp))

        StaggeredEntrance(index = 2) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyLarge.copy(
                    color = MaterialTheme.ledger.deskInk,
                    lineHeight = MaterialTheme.typography.bodyLarge.lineHeight * 1.35f
                )
            )
        }

        Spacer(modifier = Modifier.height(26.dp))

        StaggeredEntrance(index = 3) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                PAGES[page].chips.forEach { chip ->
                    GlassChip(text = chip, accent = PAGE_CHIP_ACCENTS[page])
                }
            }
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
        chips = listOf("No Cloud", "Offline", "Encrypted")
    ),
    OnboardingPageData(
        titleRes = R.string.onboarding_page3_title,
        subtitleRes = R.string.onboarding_page3_subtitle,
        chips = listOf("Vulkan GPU", "Streaming", "GGUF")
    ),
    OnboardingPageData(
        titleRes = R.string.onboarding_page4_title,
        subtitleRes = R.string.onboarding_page4_subtitle,
        chips = listOf("Download", "Switch Instantly", "Quantized")
    ),
    OnboardingPageData(
        titleRes = R.string.onboarding_page5_title,
        subtitleRes = R.string.onboarding_page5_subtitle,
        chips = listOf("No Account", "No Limits", "Yours")
    )
)
