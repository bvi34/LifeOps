package com.project.app.ui.docs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.ViewAgenda
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.project.app.logic.BlockType
import com.project.app.logic.DocBlock
import com.project.app.logic.MarkdownTables

/**
 * One block of a document, in each of the ways it can be shown: read-only, editable, and
 * as a row of a table.
 */

/**
 * A block as it reads, not as it is edited.
 *
 * Everything a reader can act on stays live here — a to-do can be ticked, a link opens, a table can
 * be turned round — because reading mode is where documents are actually used, and a checklist you
 * cannot tick while reading it is a picture of a checklist.
 */
@Composable
internal fun ReadOnlyBlock(
    block: DocBlock,
    ordinal: Int,
    tableCards: Boolean,
    onToggleTableView: () -> Unit,
    onCheck: (Boolean) -> Unit
) {
    when (block.type) {
        BlockType.DIVIDER -> HorizontalDivider(Modifier.padding(vertical = 8.dp))

        BlockType.CODE -> CodeSurface(block.text, Modifier.padding(vertical = 2.dp))

        BlockType.QUOTE -> QuoteSurface(block.text, Modifier.padding(vertical = 2.dp))

        BlockType.TABLE -> TableBlock(
            source = block.text,
            cards = tableCards,
            onToggleView = onToggleTableView
        )

        BlockType.TODO -> Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = block.checked, onCheckedChange = onCheck)
            MarkdownText(
                text = block.text,
                style = MaterialTheme.typography.bodyLarge.copy(
                    textDecoration = if (block.checked) TextDecoration.LineThrough else null
                ),
                color = if (block.checked) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(start = 8.dp)
            )
        }

        BlockType.BULLET, BlockType.NUMBERED -> Row(verticalAlignment = Alignment.Top) {
            Text(
                text = if (block.type == BlockType.NUMBERED) "$ordinal." else "•",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(28.dp)
            )
            MarkdownText(
                text = block.text,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f)
            )
        }

        else -> MarkdownText(
            text = block.text,
            style = when (block.type) {
                BlockType.HEADING1 -> MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold)
                BlockType.HEADING2 -> MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold)
                BlockType.HEADING3 -> MaterialTheme.typography.titleMedium
                else -> MaterialTheme.typography.bodyLarge
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = if (block.type.isHeading) 8.dp else 0.dp)
        )
    }
}

/**
 * A table, with the one control it needs: which way round to read it.
 *
 * The toggle sits on the table rather than in the document menu because it is a question you ask
 * *of a table* — and because a wide table's own top-right corner is where you are already looking
 * when it does not fit.
 */
@Composable
private fun TableBlock(
    source: String,
    cards: Boolean,
    onToggleView: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onToggleView) {
                Icon(
                    if (cards) Icons.Filled.GridOn else Icons.Filled.ViewAgenda,
                    contentDescription = if (cards) "Show as a grid" else "Show a row at a time"
                )
            }
        }
        MarkdownTableView(source = source, cards = cards)
    }
}

/**
 * One block, editable.
 *
 * A divider draws itself; everything else is a text field styled for what it is, so a heading
 * *looks* like a heading while you are typing it. The controls sit on the row rather than in a
 * floating toolbar because there is no selection model here to attach one to.
 */
@Composable
internal fun BlockRow(
    block: DocBlock,
    ordinal: Int,
    tableCards: Boolean,
    onToggleTableView: () -> Unit,
    onChange: (DocBlock) -> Unit,
    onRetype: (BlockType) -> Unit,
    onMove: (Int) -> Unit,
    onDelete: () -> Unit,
    onAddAfter: () -> Unit
) {
    if (block.type == BlockType.TABLE) {
        TableBlockRow(
            block = block,
            cards = tableCards,
            onToggleView = onToggleTableView,
            onChange = onChange,
            menu = { BlockMenu(block, onRetype, onMove, onDelete, onAddAfter) }
        )
        return
    }

    if (block.type == BlockType.DIVIDER) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            HorizontalDivider(Modifier.weight(1f))
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.MoreVert, contentDescription = "Remove divider")
            }
        }
        return
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (block.type == BlockType.TODO) {
            Checkbox(
                checked = block.checked,
                onCheckedChange = { onChange(block.copy(checked = it)) }
            )
        }

        // The list marker is drawn beside the field rather than inside it: it is not part of the
        // text, and a bullet you can backspace over is a bullet that ends up in the exported
        // Markdown twice.
        markerFor(block.type, ordinal)?.let { marker ->
            Text(
                marker,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 8.dp)
            )
        }

        TextField(
            value = block.text,
            onValueChange = { onChange(block.copy(text = it)) },
            modifier = Modifier.weight(1f),
            textStyle = when (block.type) {
                BlockType.HEADING1 -> MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold)
                BlockType.HEADING2 -> MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold)
                BlockType.HEADING3 -> MaterialTheme.typography.titleMedium
                BlockType.QUOTE -> MaterialTheme.typography.bodyLarge.copy(fontStyle = FontStyle.Italic)
                BlockType.CODE -> MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace)
                BlockType.TODO -> MaterialTheme.typography.bodyLarge.copy(
                    textDecoration = if (block.checked) TextDecoration.LineThrough else null
                )
                else -> MaterialTheme.typography.bodyLarge
            },
            placeholder = { Text(placeholderFor(block.type), style = MaterialTheme.typography.bodyMedium) },
            singleLine = false,
            // A code block opens with room to be one: a fence that starts as a one-line field looks
            // like a sentence you are meant to finish.
            minLines = if (block.type.isMultiline) 3 else 1,
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Color.Transparent,
                unfocusedContainerColor = Color.Transparent,
                disabledContainerColor = Color.Transparent
            )
        )

        BlockMenu(block, onRetype, onMove, onDelete, onAddAfter)
    }
}

/**
 * A table while the document is being edited.
 *
 * The table stays *drawn* — you edit the source underneath it, and see the grid redraw as you type.
 * A pipe table's source is unreadable on a phone-width line, so an editor that showed only the
 * source would be asking people to count columns by eye; and one that showed only the grid would
 * have no way to fix a cell at all.
 */
@Composable
private fun TableBlockRow(
    block: DocBlock,
    cards: Boolean,
    onToggleView: () -> Unit,
    onChange: (DocBlock) -> Unit,
    menu: @Composable () -> Unit
) {
    var showSource by remember(block.id) { mutableStateOf(false) }

    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Table",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f).padding(start = 4.dp)
            )
            TextButton(onClick = { showSource = !showSource }) {
                Text(if (showSource) "Done" else "Edit cells")
            }
            IconButton(onClick = onToggleView) {
                Icon(
                    if (cards) Icons.Filled.GridOn else Icons.Filled.ViewAgenda,
                    contentDescription = if (cards) "Show as a grid" else "Show a row at a time"
                )
            }
            menu()
        }

        MarkdownTableView(source = block.text, cards = cards)

        if (showSource) {
            TextField(
                value = block.text,
                onValueChange = { onChange(block.copy(text = it)) },
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                placeholder = { Text(placeholderFor(BlockType.TABLE), style = MaterialTheme.typography.bodySmall) },
                singleLine = false,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    disabledContainerColor = Color.Transparent
                )
            )
            TextButton(onClick = {
                MarkdownTables.coerce(block.text)?.let { onChange(block.copy(text = it.render())) }
            }) { Text("Tidy up columns") }
        }
    }
}

/**
 * The mark that stands in front of a list item or a quote, or null when nothing does.
 *
 * A numbered item shows the number it will export as, not a permanent "1." — seeing the list count
 * up is half of what tells you it is a list.
 */
private fun markerFor(type: BlockType, ordinal: Int): String? = when (type) {
    BlockType.BULLET -> "•"
    BlockType.NUMBERED -> "$ordinal."
    BlockType.QUOTE -> "▍"
    else -> null
}

private fun placeholderFor(type: BlockType): String = when (type) {
    BlockType.HEADING1 -> "Heading"
    BlockType.HEADING2 -> "Subheading"
    BlockType.HEADING3 -> "Small heading"
    BlockType.BULLET -> "List item"
    BlockType.NUMBERED -> "List item"
    BlockType.TODO -> "To do"
    BlockType.QUOTE -> "Quotation"
    BlockType.CODE -> "Code"
    BlockType.TABLE -> "| Column | Column |\n| --- | --- |\n| Cell | Cell |"
    else -> "Write something…"
}
