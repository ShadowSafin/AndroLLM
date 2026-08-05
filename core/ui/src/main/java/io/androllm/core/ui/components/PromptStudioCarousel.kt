package io.androllm.core.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Functions
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.androllm.core.ui.theme.CloudWhite
import io.androllm.core.ui.theme.MoonSilver
import io.androllm.core.ui.theme.SkyBlue
import io.androllm.core.ui.theme.SunsetCloudOrange
import io.androllm.core.ui.theme.SunsetCloudPeach

data class PromptTemplate(
    val id: String,
    val title: String,
    val category: String,
    val promptText: String,
    val icon: ImageVector,
    val estimatedTokens: Int
)

/**
 * Revolut-Inspired Prompt Studio Library & Carousel in core:ui.
 */
@Composable
fun PromptStudioCarousel(
    onPromptSelected: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val categories = listOf("All", "Coding", "Reasoning", "Creative", "Math & Logic")
    var selectedCategory by remember { mutableStateOf("All") }

    val templates = remember {
        listOf(
            PromptTemplate(
                id = "1",
                title = "Refactor & Optimize Code",
                category = "Coding",
                promptText = "Review the following code, optimize performance, and rewrite it cleanly with comments:",
                icon = Icons.Default.Code,
                estimatedTokens = 120
            ),
            PromptTemplate(
                id = "2",
                title = "First-Principles Analysis",
                category = "Reasoning",
                promptText = "Break down this problem using first-principles thinking and outline logical step-by-step solutions:",
                icon = Icons.Default.Psychology,
                estimatedTokens = 150
            ),
            PromptTemplate(
                id = "3",
                title = "Debug Kotlin Exception",
                category = "Coding",
                promptText = "Analyze this stack trace, explain the root cause, and provide a fix:",
                icon = Icons.Default.BugReport,
                estimatedTokens = 90
            ),
            PromptTemplate(
                id = "4",
                title = "Creative Brainstorming",
                category = "Creative",
                promptText = "Generate 5 unique, out-of-the-box product features for:",
                icon = Icons.Default.AutoAwesome,
                estimatedTokens = 110
            ),
            PromptTemplate(
                id = "5",
                title = "Math & Algorithm Proof",
                category = "Math & Logic",
                promptText = "Explain the time complexity and mathematical rationale behind:",
                icon = Icons.Default.Functions,
                estimatedTokens = 140
            )
        )
    }

    val filteredTemplates = remember(selectedCategory) {
        if (selectedCategory == "All") templates else templates.filter { it.category == selectedCategory }
    }

    Column(modifier = modifier.fillMaxWidth()) {
        // Category Filter Pills
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(horizontal = 4.dp)
        ) {
            items(categories) { cat ->
                val isSelected = selectedCategory == cat
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .background(if (isSelected) SunsetCloudOrange else Color(0x33E2E8F0))
                        .clickable { selectedCategory = cat }
                        .padding(horizontal = 14.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = cat,
                        fontSize = 12.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                        color = if (isSelected) CloudWhite else MoonSilver
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Cards Carousel
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(horizontal = 4.dp)
        ) {
            items(filteredTemplates) { item ->
                CloudGlassCard(
                    modifier = Modifier
                        .width(220.dp)
                        .height(130.dp),
                    onClick = { onPromptSelected(item.promptText) }
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(32.dp)
                                    .clip(CircleShape)
                                    .background(SkyBlue.copy(alpha = 0.2f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = item.icon,
                                    contentDescription = null,
                                    tint = SkyBlue,
                                    modifier = Modifier.size(18.dp)
                                )
                            }

                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Color(0x33E2E8F0))
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = "~${item.estimatedTokens} tok",
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = SunsetCloudPeach
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        Text(
                            text = item.title,
                            style = MaterialTheme.typography.titleSmall.copy(
                                fontWeight = FontWeight.Bold,
                                color = CloudWhite
                            )
                        )

                        Text(
                            text = item.promptText,
                            style = MaterialTheme.typography.labelSmall.copy(
                                color = MoonSilver.copy(alpha = 0.6f)
                            ),
                            maxLines = 2,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}
