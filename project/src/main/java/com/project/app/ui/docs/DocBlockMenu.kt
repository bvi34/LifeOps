package com.project.app.ui.docs

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.project.app.logic.BlockType
import com.project.app.logic.DocBlock

/**
 * Changing what a block is — the menu, and the dialog that lists the kinds.
 */

/** The actions any block has: what to put after it, what to turn it into, where to move it. */
@Composable
internal fun BlockMenu(
    block: DocBlock,
    onRetype: (BlockType) -> Unit,
    onMove: (Int) -> Unit,
    onDelete: () -> Unit,
    onAddAfter: () -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { menuOpen = true }) {
            Icon(Icons.Filled.MoreVert, contentDescription = "Block actions")
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(
                text = { Text("Add block below") },
                onClick = {
                    menuOpen = false
                    onAddAfter()
                }
            )
            BlockType.entries.filter { it != block.type }.forEach { type ->
                DropdownMenuItem(
                    text = { Text("Turn into ${type.label.lowercase()}") },
                    onClick = {
                        menuOpen = false
                        onRetype(type)
                    }
                )
            }
            DropdownMenuItem(
                text = { Text("Move up") },
                leadingIcon = { Icon(Icons.Filled.ArrowUpward, contentDescription = null) },
                onClick = {
                    menuOpen = false
                    onMove(-1)
                }
            )
            DropdownMenuItem(
                text = { Text("Move down") },
                leadingIcon = { Icon(Icons.Filled.ArrowDownward, contentDescription = null) },
                onClick = {
                    menuOpen = false
                    onMove(1)
                }
            )
            DropdownMenuItem(
                text = { Text("Delete block") },
                onClick = {
                    menuOpen = false
                    onDelete()
                }
            )
        }
    }
}

@Composable
internal fun BlockTypeDialog(onDismiss: () -> Unit, onPick: (BlockType) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add a block") },
        text = {
            LazyColumn {
                items(BlockType.entries.toList(), key = { it.key }) { type ->
                    TextButton(
                        onClick = { onPick(type) },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(type.label) }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
