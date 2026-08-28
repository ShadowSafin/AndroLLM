package io.androllm.feature.coding.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.androllm.core.ui.theme.ledger
import io.androllm.feature.coding.workspace.FileTreeNode

/** Premium workspace file-tree panel — spacious, readable, mobile-friendly. */
@Composable
fun FileTreePanel(
    tree: FileTreeNode?,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.ledger.deskWalnut),
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp)
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.ledger.lampAmber.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Filled.Folder, contentDescription = null, tint = MaterialTheme.ledger.lampAmber, modifier = Modifier.size(18.dp))
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        "FILES",
                        style = MaterialTheme.typography.labelLarge.copy(
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.ledger.lampDeep,
                            letterSpacing = 1.2.sp
                        )
                    )
                    Text(
                        tree?.let { "${countNodes(it)} items" } ?: "Loading…",
                        style = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.ledger.deskInkFaint)
                    )
                }
                IconButton(onClick = onClose, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Filled.Close, "Close", tint = MaterialTheme.ledger.deskInk, modifier = Modifier.size(20.dp))
                }
            }

            Spacer(Modifier.height(10.dp))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.ledger.deskWalnutDeep)
                    .padding(12.dp)
            ) {
                if (tree == null) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(8.dp)) {
                        Icon(Icons.Filled.Folder, contentDescription = null, tint = MaterialTheme.ledger.deskInkFaint, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Loading file tree…", fontSize = 13.sp, color = MaterialTheme.ledger.deskInk, fontWeight = FontWeight.Medium)
                    }
                } else {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        TreeNode(tree, indent = 0, isRoot = true)
                    }
                }
            }

            Spacer(Modifier.height(6.dp))
            Text(
                "Read-only inspection — tap Files again to hide.",
                style = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.ledger.deskInkFaint)
            )
        }
    }
}

private fun countNodes(node: FileTreeNode): Int {
    var c = node.children.size
    node.children.forEach { if (it.isDirectory) c += countNodes(it) }
    return c
}

@Composable
private fun TreeNode(node: FileTreeNode, indent: Int, isRoot: Boolean = false) {
    if (!isRoot) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = (indent * 16).dp, top = 5.dp, bottom = 5.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(if (node.isDirectory) Color.Transparent else MaterialTheme.colorScheme.surface.copy(alpha = 0.0f))
                .padding(horizontal = 6.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (node.isDirectory) Icons.Filled.Folder else Icons.Filled.Description,
                contentDescription = null,
                tint = if (node.isDirectory) MaterialTheme.ledger.lampAmber else MaterialTheme.ledger.deskInk,
                modifier = Modifier.size(16.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                node.name + if (node.isDirectory) "/" else "",
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
                fontWeight = if (node.isDirectory) FontWeight.SemiBold else FontWeight.Normal,
                color = if (node.isDirectory) MaterialTheme.ledger.deskPaper else MaterialTheme.ledger.deskPaperDim
            )
        }
    }
    node.children.forEach { TreeNode(it, indent + if (isRoot) 0 else 1) }
}
