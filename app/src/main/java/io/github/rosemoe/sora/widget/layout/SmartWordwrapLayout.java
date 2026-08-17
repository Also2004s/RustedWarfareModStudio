/*
 *    sora-editor - the awesome code editor for Android
 *    https://github.com/Rosemoe/sora-editor
 *    Copyright (C) 2020-2024  Rosemoe
 *
 *     This library is free software; you can redistribute it and/or
 *     modify it under the terms of the GNU Lesser General Public
 *     License as published by the Free Software Foundation; either
 *     version 2.1 of the License, or (at your option) any later version.
 *
 *     This library is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 *     Lesser General Public License for more details.
 *
 *     You should have received a copy of the GNU Lesser General Public
 *     License along with this library; if not, write to the Free Software
 *     Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301
 *     USA
 *
 *     Please contact Rosemoe by email 2073412493@qq.com if you need
 *     additional information or have any questions
 */
package io.github.rosemoe.sora.widget.layout;

import android.util.Log;
import android.util.SparseArray;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.NoSuchElementException;

import io.github.rosemoe.sora.graphics.Paint;
import io.github.rosemoe.sora.graphics.TextRow;
import io.github.rosemoe.sora.lang.analysis.StyleUpdateRange;
import io.github.rosemoe.sora.lang.styling.Span;
import io.github.rosemoe.sora.lang.styling.SpanFactory;
import io.github.rosemoe.sora.lang.styling.TextStyle;
import io.github.rosemoe.sora.lang.styling.inlayHint.InlayHint;
import io.github.rosemoe.sora.text.Content;
import io.github.rosemoe.sora.text.ContentLine;
import io.github.rosemoe.sora.util.IntPair;
import io.github.rosemoe.sora.widget.CodeEditor;
import com.rwmodstudio.editor.PriorityBreaks;
import com.rwmodstudio.editor.SmartWrapBreaks;

/**
 * Wordwrap layout for editor
 * <p>
 * This layout will not let character displayed outside the editor's width
 * <p>
 * However, using this can be power-costing because we will have to recreate this layout in various
 * conditions, such as when the line number increases and its width grows or when the text size has changed
 *
 * @author Rose
 *
 * Modified by RustedWarfareModStudio (2026-08-14): smart logical line breaks
 * (break before literal \n, before and/or, before (xxx or xxx) groups, and before
 * + / - followed by a named operand), keeping the rest of the layout unchanged.
 */
public class SmartWordwrapLayout extends WordwrapLayout {

    private static final String TAG = "SmartWordwrapLayout";

    /**
     * When measuring text in wordwrap mode, we must use the max possible width of the character sequence
     * so that no character will be invisible after its styles are applied on actual drawing.
     */

    private final static List<Span> sSpansForWordwrap = new ArrayList<>();

    static {
        sSpansForWordwrap.add(SpanFactory.obtainNoExt(0, TextStyle.makeStyle(0, 0, true, true, false)));
    }

    private final int width;
    private final float miniGraphWidth;
    private final boolean antiWordBreaking;
    private final boolean supportRtlRow;
    private List<RowRegion> rowTable;

    public SmartWordwrapLayout(@NonNull CodeEditor editor, @NonNull Content text, boolean antiWordBreaking, boolean supportRtlRow, @Nullable WordwrapLayout oldLayout, boolean clearCache) {
        super(editor, text, antiWordBreaking, supportRtlRow, null, clearCache);
        this.antiWordBreaking = antiWordBreaking;
        this.supportRtlRow = supportRtlRow;
        // 本类不使用基类 WordwrapLayout 的私有 rowTable（sora-editor 0.24.6 中为 private），
        // 声明自己的 rowTable 并用智能断行重建；基类构造器触发的默认断行任务会被忽略。
        rowTable = new ArrayList<>();
        miniGraphWidth = (editor.getNonPrintablePaintingFlags() & CodeEditor.FLAG_DRAW_SOFT_WRAP) != 0 ?
                editor.getRenderer().getMiniGraphWidth() : 0f;
        width = editor.getWidth() - (int) (editor.measureTextRegionOffset() + editor.getTextPaint().measureText("a")) - (int) miniGraphWidth * 2;
        breakAllLines();
    }

    @Override
    protected void submitTask(@NonNull LayoutTask<?> task) {
        // 基类 WordwrapLayout 构造器会先触发一次默认断行的 breakAllLines()，
        // 其任务若真正执行，会在本类 rowTable 填充完成前回调 setLayoutBusy(false)，
        // 导致缩放恢复滚动位置时错误回到首行。这里直接忽略基类的默认断行任务，
        // 只执行本类（SmartWordwrapLayout）的智能断行任务。
        if (task.getClass().getEnclosingClass() == WordwrapLayout.class) {
            return;
        }
        super.submitTask(task);
    }

    private void breakAllLines() {
        var taskCount = Math.min(SUBTASK_COUNT, (int) Math.ceil((float) text.getLineCount() / MIN_LINE_COUNT_FOR_SUBTASK));
        var sizeEachTask = text.getLineCount() / taskCount;
        var monitor = new TaskMonitor(taskCount, (results, cancelledCount) -> {
            final var editor = this.editor;
            if (editor != null) {
                List<WordwrapResult> r2 = new ArrayList<>();
                for (Object result : results) {
                    r2.add((WordwrapResult) result);
                }
                Collections.sort(r2);
                editor.postInLifecycle(() -> {
                    if (SmartWordwrapLayout.this.editor != editor) {
                        // This layout could have been abandoned when waiting for Runnable execution
                        // See #307
                        return;
                    }
                    if (rowTable != null) {
                        rowTable.clear();
                    } else {
                        rowTable = new ArrayList<>();
                    }
                    for (WordwrapResult wordwrapResult : r2) {
                        rowTable.addAll(wordwrapResult.regions);
                    }
                    editor.setLayoutBusy(false);
                    editor.getEventHandler().scrollBy(0, 0);
                });
            }
        });
        editor.setLayoutBusy(true);
        for (int i = 0; i < taskCount; i++) {
            var start = sizeEachTask * i;
            var end = i + 1 == taskCount ? (text.getLineCount() - 1) : (sizeEachTask * (i + 1) - 1);
            submitTask(new WordwrapAnalyzeTask(monitor, i, start, end));
        }
    }

    private int findRow(int line) {
        // 布局尚未异步填充完成时（例如缩放触发的 setLayoutBusy(false) 回调），
        // rowTable 可能仍为空，此时直接返回 0，避免越界崩溃。
        if (rowTable == null || rowTable.isEmpty()) {
            return 0;
        }
        int index;
        // Binary find line
        int left = 0, right = rowTable.size();
        while (left <= right) {
            var mid = (left + right) / 2;
            if (mid < 0 || mid >= rowTable.size()) {
                left = Math.max(0, Math.min(rowTable.size() - 1, mid));
                break;
            }
            int value = rowTable.get(mid).line;
            if (value < line) {
                left = mid + 1;
            } else if (value > line) {
                right = mid - 1;
            } else {
                left = mid;
                break;
            }
        }
        index = left;
        // 防御：命中区域不属于目标行时（内容变更后行表暂未完全就绪），就近定位到目标行的首个区域；
        // 找不到则回退到目标行之前最近的行区域，绝不返回会导致视图滚到顶部的错误行。
        if (index >= 0 && index < rowTable.size() && rowTable.get(index).line != line) {
            index = locateLine(line, index);
        }
        while (index > 0 && rowTable.get(index).startColumn > 0) {
            index--;
        }
        return index;
    }

    /**
     * 在行表中就近定位 line 的首个行区域（从 near 开始向前，再向后）；
     * 找不到则回退到 line 之前最近的行区域索引，保证不为 0/越界（除非整表都在 line 之后）。
     */
    private int locateLine(int line, int near) {
        int size = rowTable.size();
        for (int i = near; i >= 0; i--) {
            if (rowTable.get(i).line == line) {
                return i;
            }
        }
        for (int i = near + 1; i < size; i++) {
            if (rowTable.get(i).line == line) {
                return i;
            }
        }
        int fallback = Math.max(0, Math.min(near, size - 1));
        for (int i = fallback; i >= 0; i--) {
            if (rowTable.get(i).line < line) {
                return i;
            }
        }
        return Math.max(0, Math.min(near, size - 1));
    }

    public int findRow(int line, int column) {
        if (rowTable == null || rowTable.isEmpty()) {
            return 0;
        }
        int row = findRow(line);
        while (rowTable.get(row).endColumn <= column && row + 1 < rowTable.size() && rowTable.get(row + 1).line == line) {
            row++;
        }
        return row;
    }

    private void breakLines(int startLine, int endLine) {
        int insertPosition = 0;
        while (insertPosition < rowTable.size()) {
            if (rowTable.get(insertPosition).line < startLine) {
                insertPosition++;
            } else {
                break;
            }
        }
        while (insertPosition < rowTable.size()) {
            int line = rowTable.get(insertPosition).line;
            if (line >= startLine && line <= endLine) {
                rowTable.remove(insertPosition);
            } else {
                break;
            }
        }
        List<RowRegion> newRegions = new ArrayList<>();
        for (int i = startLine; i <= endLine; i++) {
            newRegions.addAll(breakLine(i, text.getLine(i), null));
        }
        rowTable.addAll(insertPosition, newRegions);
    }

    /**
     * Break a single line
     */
    /**
     * Break a single line, preferring logical break points (smart wrap).
     * <p>
     * When there is no preferred break point, or every segment fits in one row, the behavior is
     * identical to the default width-based breaking. When a segment between two preferred break
     * points is still wider than the editor, it falls back to the default breaking for that segment.
     */
    private List<RowRegion> breakLine(int line, ContentLine sequence, Paint paint) {
        Paint p = paint;
        if (p == null) {
            p = new Paint(editor.isRenderFunctionCharacters());
            p.set(editor.getTextPaint());
        }
        var tr = new TextRow();
        var directions = text.getLineDirections(line);
        tr.set(sequence, 0, sequence.length(), sSpansForWordwrap, getInlayHints(line), directions, p, null, editor.getRenderer().createTextRowParams());

        boolean isRtlBased = false;
        if (supportRtlRow && sequence.mayNeedBidi()) {
            int minRunLevel = Integer.MAX_VALUE;
            for (int i = 0; i < directions.getRunCount(); i++) {
                minRunLevel = Math.min(minRunLevel, directions.getRunLevel(i));
            }
            if ((minRunLevel & 1) != 0) {
                isRtlBased = true;
            }
        }

        PriorityBreaks pb = SmartWrapBreaks.INSTANCE.computePriorityBreaks(sequence);
        int[] parenGroups = pb.getParenGroups();
        int[] andOr = pb.getAndOr();
        int[] operators = pb.getOperators();
        int[] commas = pb.getCommas();
        var results = new ArrayList<RowRegion>();
        int len = sequence.length();
        // 空行也必须产生一个行区域（sora 原版 breakText 的语义：每行至少一行），
        // 否则按回车产生的新空行在行表里没有区域，光标/滚动映射失败 → 不换行 + 跳首行。
        if (len == 0) {
            results.add(makeRegion(line, 0, 0, tr, isRtlBased));
            return results;
        }
        int rowStart = 0;
        int pi = 0, ai = 0, oi = 0, ci = 0;
        while (rowStart < len) {
            // 字面 \n 是硬断点：本行以它为上界，绝不越过；\n 显示在新行行首。
            int limit = nextBackslashN(sequence, rowStart);
            if (limit < 0) {
                limit = len;
            }
            // 整段（到下一个 \n 或行尾）放得下时直接成一行。
            tr.setRange(rowStart, limit);
            var whole = tr.breakText(width, antiWordBreaking);
            if (whole.size() == 1) {
                results.add(makeRegion(line, rowStart, limit, tr, isRtlBased));
                rowStart = limit;
            } else {
                // 按优先级选断点：括号组 > and/or > 运算符号(+ - *) > 逗号。
                int chosen = farthestFitting(tr, parenGroups, pi, rowStart, limit);
                if (chosen < 0) {
                    chosen = farthestFitting(tr, andOr, ai, rowStart, limit);
                }
                if (chosen < 0) {
                    chosen = farthestFitting(tr, operators, oi, rowStart, limit);
                }
                if (chosen < 0) {
                    chosen = farthestFitting(tr, commas, ci, rowStart, limit);
                }
                if (chosen >= 0) {
                    results.add(makeRegion(line, rowStart, chosen, tr, isRtlBased));
                    rowStart = chosen;
                } else {
                    // 兜底一：主标点贪婪（排除 = 相邻、排除汉字内部、不含 .）。
                    int[] fb = SmartWrapBreaks.INSTANCE.computeFallbackBreaks(sequence, rowStart, limit);
                    int fbChosen = farthestFitting(tr, fb, 0, rowStart, limit);
                    if (fbChosen >= 0) {
                        results.add(makeRegion(line, rowStart, fbChosen, tr, isRtlBased));
                        rowStart = fbChosen;
                    } else {
                        // 兜底一点五：. 兜底（仅当主标点放不下时才用，避免 .resource 前导）。
                        int[] dot = SmartWrapBreaks.INSTANCE.computeDotBreaks(sequence, rowStart, limit);
                        int dotChosen = farthestFitting(tr, dot, 0, rowStart, limit);
                        if (dotChosen >= 0) {
                            results.add(makeRegion(line, rowStart, dotChosen, tr, isRtlBased));
                            rowStart = dotChosen;
                        } else {
                            // 兜底二：CJK 安全硬断（不在两个汉字之间、不在 = 相邻处断）。
                            int safe = cjkSafeBreak(sequence, rowStart, limit, tr);
                            results.add(makeRegion(line, rowStart, safe, tr, isRtlBased));
                            rowStart = safe;
                        }
                    }
                }
            }
            while (pi < parenGroups.length && parenGroups[pi] <= rowStart) pi++;
            while (ai < andOr.length && andOr[ai] <= rowStart) ai++;
            while (oi < operators.length && operators[oi] <= rowStart) oi++;
            while (ci < commas.length && commas[ci] <= rowStart) ci++;
        }
        // 防御：任何行都必须至少一个行区域（与 sora 原版一致）。
        if (results.isEmpty()) {
            results.add(makeRegion(line, 0, len, tr, isRtlBased));
        }
        return results;
    }

    /** 在 positions 中找 (rowStart, limit] 内最远放得下的断点；无则返回 -1。 */
    private int farthestFitting(TextRow tr, int[] positions, int fromIndex, int rowStart, int limit) {
        int lastFit = -1;
        for (int i = fromIndex; i < positions.length; i++) {
            int p = positions[i];
            if (p <= rowStart) {
                continue;
            }
            if (p > limit) {
                break;
            }
            tr.setRange(rowStart, p);
            var rows = tr.breakText(width, antiWordBreaking);
            if (rows.size() == 1) {
                lastFit = p;
            } else {
                break;
            }
        }
        return lastFit;
    }

    /** 生成 [rowStart, end) 的行区域（end 处保证放得下）。 */
    private RowRegion makeRegion(int line, int rowStart, int end, TextRow tr, boolean isRtlBased) {
        tr.setRange(rowStart, end);
        var rows = tr.breakText(width, false);
        float w = 0f;
        List<InlayHint> hints = null;
        if (!rows.isEmpty()) {
            w = rows.get(0).rowWidth;
            hints = rows.get(0).inlayHints;
        }
        return new RowRegion(line, rowStart, end, hints, w, isRtlBased);
    }

    /** 找 from 之后（不含 from 本身）的下一个字面 \n 的 \ 位置；无则返回 -1。 */
    private static int nextBackslashN(CharSequence line, int from) {
        int i = from;
        int n = line.length();
        while (i < n - 1) {
            if (i > from && line.charAt(i) == '\\' && line.charAt(i + 1) == 'n') {
                return i;
            }
            i++;
        }
        return -1;
    }

    /**
     * CJK 安全硬断
    /**
     * CJK 安全硬断：在 (start, first) 内找「放得下、不在两个汉字之间、且不在 = 相邻处」的最远位置。
     * 若找不到（单个汉字串或 = 相邻串比屏宽还长），退而取最远放得下的位置（已知限制，罕见）。
     */
    private int cjkSafeBreak(CharSequence line, int start, int first, TextRow tr) {
        int lo = start + 1, hi = first - 1, maxFit = -1;
        while (lo <= hi) {
            int mid = (lo + hi) / 2;
            tr.setRange(start, mid);
            var rows = tr.breakText(width, false);
            if (rows.size() == 1) {
                maxFit = mid;
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        if (maxFit == -1) {
            return Math.min(first, start + 1);
        }
        for (int p = maxFit; p > start; p--) {
            char cur = line.charAt(p);
            char prev = line.charAt(p - 1);
            boolean bad = (cur == '=' || prev == '=')
                    || (SmartWrapBreaks.INSTANCE.isHan(prev) && SmartWrapBreaks.INSTANCE.isHan(cur));
            if (!bad) {
                return p;
            }
        }
        return maxFit;
    }

    @Override
    public void beforeReplace(@NonNull Content content) {
        // Intentionally empty
    }

    @Override
    public void afterInsert(@NonNull Content content, int startLine, int startColumn, int endLine, int endColumn, @NonNull CharSequence insertedContent) {
        super.afterInsert(content, startLine, startColumn, endLine, endColumn, insertedContent);
        // 防御：行表尚未就绪时直接全量重建，避免增量逻辑在空/失效行表上出错。
        if (rowTable == null || rowTable.isEmpty()) {
            Log.w(TAG, "afterInsert on empty rowTable; full rebuild");
            breakAllLines();
            return;
        }
        // Update line numbers
        int delta = endLine - startLine;
        if (delta != 0) {
            for (int row = findRow(startLine + 1); row < rowTable.size(); row++) {
                rowTable.get(row).line += delta;
            }
        }
        // Re-break
        breakLines(startLine, endLine);
    }

    @Override
    public void afterDelete(@NonNull Content content, int startLine, int startColumn, int endLine, int endColumn, @NonNull CharSequence deletedContent) {
        super.afterDelete(content, startLine, startColumn, endLine, endColumn, deletedContent);
        // 防御：行表尚未就绪时直接全量重建。
        if (rowTable == null || rowTable.isEmpty()) {
            Log.w(TAG, "afterDelete on empty rowTable; full rebuild");
            breakAllLines();
            return;
        }
        int delta = endLine - startLine;
        if (delta != 0) {
            int startRow = findRow(startLine);
            while (startRow < rowTable.size()) {
                int line = rowTable.get(startRow).line;
                if (line >= startLine && line <= endLine) {
                    rowTable.remove(startRow);
                } else {
                    break;
                }
            }
            for (int row = findRow(endLine + 1); row < rowTable.size(); row++) {
                var region = rowTable.get(row);
                if (region.line >= endLine)
                    region.line -= delta;
            }
        }
        breakLines(startLine, startLine);
    }

    @Override
    public void destroyLayout() {
        super.destroyLayout();
        rowTable = null;
    }

    @NonNull
    @Override
    public Row getRowAt(int rowIndex) {
        if (rowTable.isEmpty()) {
            var r = new Row();
            r.startColumn = 0;
            r.endColumn = text.getColumnCount(rowIndex);
            r.isLeadingRow = true;
            r.isTrailingRow = true;
            r.lineIndex = rowIndex;
            r.inlayHints = getInlayHints(rowIndex);
            return r;
        }
        var region = rowTable.get(rowIndex);
        var isLeadingRow = rowIndex <= 0 || rowTable.get(rowIndex - 1).line != region.line;
        var isTrailingRow = rowIndex + 1 >= rowTable.size() || rowTable.get(rowIndex + 1).line != region.line;
        return rowTable.get(rowIndex).toRow(isLeadingRow, isTrailingRow, width);
    }

    @Override
    public int getLineNumberForRow(int row) {
        if (rowTable.isEmpty()) {
            return Math.max(0, Math.min(row, text.getLineCount() - 1));
        }
        return row >= rowTable.size() ? rowTable.get(rowTable.size() - 1).line : rowTable.get(row).line;
    }

    @NonNull
    @Override
    public RowIterator obtainRowIterator(int initialRow, @Nullable SparseArray<ContentLine> preloadedLines) {
        return rowTable.isEmpty() ? new LineBreakLayout.LineBreakLayoutRowItr(this, text, initialRow, preloadedLines) : new WordwrapLayoutRowItr(initialRow);
    }

    @Override
    public long getUpPosition(int line, int column) {
        if (rowTable.isEmpty()) {
            if (line - 1 < 0) {
                return IntPair.pack(0, 0);
            }
            int c_column = text.getColumnCount(line - 1);
            if (column > c_column) {
                column = c_column;
            }
            return IntPair.pack(line - 1, column);
        }
        int row = findRow(line, column);
        if (row > 0) {
            var offset = column - rowTable.get(row).startColumn;
            var lastRow = rowTable.get(row - 1);
            var max = lastRow.endColumn - lastRow.startColumn;
            offset = Math.min(offset, max);
            return IntPair.pack(lastRow.line, lastRow.startColumn + offset);
        }
        return IntPair.pack(0, 0);
    }

    @Override
    public long getDownPosition(int line, int column) {
        if (rowTable.isEmpty()) {
            int c_line = text.getLineCount();
            if (line + 1 >= c_line) {
                return IntPair.pack(line, text.getColumnCount(line));
            } else {
                int c_column = text.getColumnCount(line + 1);
                if (column > c_column) {
                    column = c_column;
                }
                return IntPair.pack(line + 1, column);
            }
        }
        int row = findRow(line, column);
        if (row + 1 < rowTable.size()) {
            var offset = column - rowTable.get(row).startColumn;
            var nextRow = rowTable.get(row + 1);
            var max = nextRow.endColumn - nextRow.startColumn;
            offset = Math.min(offset, max);
            return IntPair.pack(nextRow.line, nextRow.startColumn + offset);
        } else {
            return IntPair.pack(line, text.getColumnCount(line));
        }
    }

    @Override
    public int getLayoutWidth() {
        return 0;
    }

    @Override
    public int getLayoutHeight() {
        if (rowTable.isEmpty()) {
            return editor.getRowHeight() * text.getLineCount();
        }
        return rowTable.size() * editor.getRowHeight();
    }

    @Override
    public int getRowIndexForPosition(int index) {
        var pos = editor.getText().getIndexer().getCharPosition(index);
        var line = pos.line;
        if (rowTable.isEmpty()) {
            return line;
        }
        var column = pos.column;
        int row = findRow(line);
        if (row < rowTable.size()) {
            var region = rowTable.get(row);
            if (region.line != line) {
                // 防御：行表短暂失效时返回就近行（clamp），绝不返回 0。
                return Math.max(0, Math.min(row, rowTable.size() - 1));
            }
            while (region.startColumn < column && row + 1 < rowTable.size()) {
                row++;
                region = rowTable.get(row);
                if (region.line != line || region.startColumn > column) {
                    row--;
                    break;
                }
            }
            return row;
        }
        return 0;
    }

    @Override
    public void invalidateLines(StyleUpdateRange range) {
        var itr = range.lineIndexIterator(text.getLineCount() - 1);
        while (itr.hasNext()) {
            var line = itr.nextInt();
            breakLines(line, line);
        }
    }

    @NonNull
    @Override
    public VisualLocation getVisualPositionForLayoutOffset(float offsetX, float offsetY) {
        if (rowTable.isEmpty()) {
            int lineCount = text.getLineCount();
            int line = Math.min(lineCount - 1, Math.max((int) (offsetY / editor.getRowHeight()), 0));
            var tr = editor.getRenderer().createTextRow(line);
            var pos = tr.getElementPositionForCursorOffset(offsetX);
            return new VisualLocation(line, pos.textOffset, pos.element, pos.isInElementBounds);
        }
        int row = (int) (offsetY / editor.getRowHeight());
        row = Math.max(0, Math.min(row, rowTable.size() - 1));
        RowRegion region = rowTable.get(row);
        if (region.startColumn != 0) {
            offsetX -= miniGraphWidth;
        }
        offsetX -= region.getRenderTranslateX(width);
        var tr = editor.getRenderer().createTextRow(row);
        var pos = tr.getElementPositionForCursorOffset(offsetX);
        int column = pos.textOffset;
        // 软换行边界修正：本行以空白结尾、且命中列落在行尾（即下一行起始）时，
        // 把光标回退到本行最后一个可见字符之后，避免点击行尾时光标跳到下一行。
        if (column >= region.endColumn
                && region.endColumn < text.getColumnCount(region.line)
                && region.endColumn > region.startColumn
                && Character.isWhitespace(text.getLine(region.line).charAt(region.endColumn - 1))) {
            int lastVisible = region.endColumn - 1;
            while (lastVisible > region.startColumn
                    && Character.isWhitespace(text.getLine(region.line).charAt(lastVisible - 1))) {
                lastVisible--;
            }
            column = lastVisible;
        }
        return new VisualLocation(region.line, column, pos.element, pos.isInElementBounds);
    }

    @NonNull
    @Override
    public float[] getCharLayoutOffset(int line, int column, float[] dest) {
        if (dest == null || dest.length < 2) {
            dest = new float[2];
        }
        if (rowTable.isEmpty()) {
            dest[0] = editor.getRowBottom(line);
            var tr = editor.getRenderer().createTextRow(line);
            dest[1] = tr.getCursorOffsetForIndex(column);
            return dest;
        }
        int row = findRow(line);
        if (row < rowTable.size()) {
            RowRegion region = rowTable.get(row);
            if (region.line != line) {
                // 防御：行表短暂失效时返回按逻辑行计算的安全位置，避免 ensurePositionVisible 滚到顶部。
                Log.w(TAG, "getCharLayoutOffset stale row for line " + line);
                dest[0] = editor.getRowBottom(line);
                var staleTr = editor.getRenderer().createTextRow(line);
                dest[1] = staleTr.getCursorOffsetForIndex(column);
                return dest;
            }
            while (region.startColumn < column && row + 1 < rowTable.size()) {
                row++;
                region = rowTable.get(row);
                if (region.line != line || region.startColumn > column) {
                    row--;
                    region = rowTable.get(row);
                    break;
                }
            }
            dest[0] = editor.getRowBottom(row);
            var tr = editor.getRenderer().createTextRow(row);
            dest[1] = tr.getCursorOffsetForIndex(column);
            if (region.startColumn != 0) {
                dest[1] += miniGraphWidth;
            }
            dest[1] += region.getRenderTranslateX(width);
        } else {
            dest[0] = dest[1] = 0;
        }
        return dest;
    }

    @Override
    public int getRowCountForLine(int line) {
        if (rowTable.isEmpty()) {
            return 1;
        }
        int row = findRow(line);
        int count = 0;
        while (row < rowTable.size() && rowTable.get(row).line == line) {
            count++;
            row++;
        }
        return count;
    }

    /**
     * Get soft breaks on the given line
     */
    public List<Integer> getSoftBreaksForLine(int line) {
        if (rowTable.isEmpty()) {
            return Collections.emptyList();
        }
        int row = findRow(line);
        var list = new ArrayList<Integer>();
        while (row < rowTable.size() && rowTable.get(row).line == line) {
            var column = rowTable.get(row).startColumn;
            if (column != 0) {
                list.add(column);
            }
            row++;
        }
        return list;
    }

    @Override
    public int getRowCount() {
        if (rowTable.isEmpty()) {
            return text.getLineCount();
        }
        return rowTable.size();
    }

    static class RowRegion {

        final int startColumn;
        final int endColumn;
        List<InlayHint> inlayHints;
        int line;
        float rowWidth;
        boolean displayFromRight;

        RowRegion(int line, int start, int end, List<InlayHint> inlayHints, float rowWidth, boolean displayFromRight) {
            this.line = line;
            startColumn = start;
            endColumn = end;
            this.inlayHints = inlayHints;
            this.rowWidth = rowWidth;
            this.displayFromRight = displayFromRight;
        }

        public Row toRow(boolean isLeadingRow, boolean isTrailingRow, float layoutWidth) {
            var row = new Row();
            row.isLeadingRow = isLeadingRow;
            row.isTrailingRow = isTrailingRow;
            row.startColumn = startColumn;
            row.endColumn = endColumn;
            row.lineIndex = line;
            row.inlayHints = inlayHints == null ? Collections.emptyList() : inlayHints;
            row.renderTranslateX = getRenderTranslateX(layoutWidth);
            return row;
        }

        public float getRenderTranslateX(float layoutWidth) {
            return displayFromRight && layoutWidth > rowWidth ? layoutWidth - rowWidth : 0f;
        }

        @NonNull
        @Override
        public String toString() {
            return "RowRegion{" +
                    "startColumn=" + startColumn +
                    ", endColumn=" + endColumn +
                    ", line=" + line +
                    '}';
        }
    }

    private static class WordwrapResult implements Comparable<WordwrapResult> {

        int index;
        List<RowRegion> regions;

        public WordwrapResult(int idx, List<RowRegion> r) {
            index = idx;
            regions = r;
        }

        @Override
        public int compareTo(WordwrapResult wordwrapResult) {
            return Integer.compare(index, wordwrapResult.index);
        }
    }

    class WordwrapLayoutRowItr implements RowIterator {

        private final Row result;
        private final int initRow;
        private int currentRow;

        WordwrapLayoutRowItr(int initialRow) {
            initRow = currentRow = initialRow;
            result = new Row();
        }

        @NonNull
        @Override
        public Row next() {
            if (!hasNext()) {
                throw new NoSuchElementException();
            }
            RowRegion region = rowTable.get(currentRow);
            result.lineIndex = region.line;
            result.startColumn = region.startColumn;
            result.endColumn = region.endColumn;
            result.inlayHints = region.inlayHints == null ? Collections.emptyList() : region.inlayHints;
            result.isLeadingRow = currentRow <= 0 || rowTable.get(currentRow - 1).line != region.line;
            result.isTrailingRow = currentRow + 1 >= rowTable.size() || rowTable.get(currentRow + 1).line != region.line;
            result.renderTranslateX = region.getRenderTranslateX(width);
            currentRow++;
            return result;
        }

        @Override
        public boolean hasNext() {
            return currentRow >= 0 && currentRow < rowTable.size();
        }

        @Override
        public void reset() {
            currentRow = initRow;
        }
    }

    private class WordwrapAnalyzeTask extends LayoutTask<WordwrapResult> {

        private final int start, end, id;
        private final Paint paint;

        WordwrapAnalyzeTask(TaskMonitor monitor, int id, int start, int end) {
            super(monitor);
            this.start = start;
            this.id = id;
            this.end = end;
            paint = new Paint(editor.isRenderFunctionCharacters());
            paint.set(editor.getTextPaint());
            paint.onAttributeUpdate();
        }

        @Override
        protected WordwrapResult compute() {
            var list = new ArrayList<RowRegion>();
            text.runReadActionsOnLines(start, end, (int index, ContentLine line, Content.ContentLineConsumer2.AbortFlag abortFlag) -> {
                try {
                    list.addAll(breakLine(index, line, paint));
                } catch (Exception e) {
                    // 防御：单行断行异常不能拖垮整个任务（否则 monitor 永不完成、layoutBusy 卡 true，输入被禁用）。
                    Log.w(TAG, "breakLine failed at line " + index, e);
                }
                if (!shouldRun()) {
                    abortFlag.set = true;
                }
            });
            return new WordwrapResult(id, list);
        }
    }

}

