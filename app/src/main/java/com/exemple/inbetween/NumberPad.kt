package com.example.inbetween

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToLong

/*
 * The number keypad used for every number the person types (the same pad the frame-length chronometer has), replacing the Android keyboard.
 *  - The value being edited opens SELECTED as a whole: tapping a digit replaces it at once; tapping + - x / continues from it (so "x 2" doubles it).
 *  - + - x / work on the pad: the line shows the expression and a "= result" preview, "=" collapses it, OK applies the result.
 * Text (names) still uses the normal keyboard: a number pad cannot type them.
 */

/** Evaluates "12+3x4", with + - x / (also - * /), decimals and a leading or following sign ("5x-2"). null when it is incomplete, not a number or divides by zero. */
fun evalExpression(src: String): Double? {
    val s = src.replace('\u2212', '-').replace('\u00D7', '*').replace('\u00F7', '/').replace(" ", "")
    if (s.isEmpty()) return null
    var i = 0
    fun number(): Double? {
        val st = i
        while (i < s.length && (s[i].isDigit() || s[i] == '.')) i++
        return if (i == st) null else s.substring(st, i).toDoubleOrNull()
    }
    fun factor(): Double? {
        if (i < s.length && (s[i] == '-' || s[i] == '+')) { val neg = s[i] == '-'; i++; val f = factor() ?: return null; return if (neg) -f else f }
        return number()
    }
    fun term(): Double? {
        var v = factor() ?: return null
        while (i < s.length && (s[i] == '*' || s[i] == '/')) { val op = s[i]; i++; val r = factor() ?: return null; v = if (op == '*') v * r else v / r }
        return v
    }
    fun expr(): Double? {
        var v = term() ?: return null
        while (i < s.length && (s[i] == '+' || s[i] == '-')) { val op = s[i]; i++; val r = term() ?: return null; v = if (op == '+') v + r else v - r }
        return v
    }
    val v = expr() ?: return null
    return if (i == s.length && v.isFinite()) v else null
}

/** A number as short text: whole numbers without a decimal point, otherwise up to 6 decimals with the trailing zeros cut. */
fun formatNumber(v: Double, decimals: Boolean = true): String {
    if (!decimals) return v.roundToLong().toString()
    if (v == floor(v) && abs(v) < 1e12) return v.roundToLong().toString()
    return java.math.BigDecimal(v).setScale(6, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
}

private const val PAD_OPS = "+\u2212\u00D7\u00F7"

/**
 * The keypad dialog. [initial] opens selected as a whole. [decimals] false = whole numbers only (no point key, the result is rounded). The result is kept
 * within [min]..[max] when given. [onResult] gets the evaluated value; [onDismiss] is always called afterwards (OK) or on Cancel.
 */
@Composable
fun NumberPadDialog(title: String, initial: String, decimals: Boolean, min: Double?, max: Double?, onResult: (Double) -> Unit, onDismiss: () -> Unit) {
    var buf by remember { mutableStateOf(initial) }
    var selected by remember { mutableStateOf(true) }
    val primary = MaterialTheme.colorScheme.primary
    fun lastOperand(): String { val i = buf.indexOfLast { it in PAD_OPS }; return buf.substring(i + 1) }
    fun key(k: String) {
        when {
            k == "C" -> { buf = ""; selected = false }
            k == "\u232B" -> { buf = if (selected) "" else buf.dropLast(1); selected = false }
            k == "=" -> evalExpression(buf)?.let { buf = formatNumber(it, decimals); selected = true }
            k == "\u00B1" -> {
                if (buf.isEmpty()) buf = "\u2212"
                else {
                    val operand = lastOperand(); val start = buf.length - operand.length
                    buf = when {
                        operand.isEmpty() -> buf + "\u2212"
                        start > 0 && buf[start - 1] == '\u2212' && (start - 1 == 0 || buf[start - 2] in PAD_OPS) -> buf.removeRange(start - 1, start)
                        else -> buf.substring(0, start) + "\u2212" + buf.substring(start)
                    }
                }
                selected = false
            }
            k in PAD_OPS.map { it.toString() } -> {
                if (selected) { selected = false; buf += k }
                else if (buf.isEmpty()) { if (k == "\u2212") buf = k }
                else {
                    val last = buf.last()
                    if (last in PAD_OPS) {
                        if (k == "\u2212" && (last == '\u00D7' || last == '\u00F7')) buf += k
                        else if (buf.length > 1) buf = buf.dropLast(1) + k
                    } else buf += k
                }
            }
            else -> {
                if (k == "." && !decimals) return
                if (selected) { buf = if (k == ".") "0." else k; selected = false }
                else if (buf.length < 30) {
                    if (k == ".") { val op = lastOperand(); if (op.contains('.')) return; buf += if (op.isEmpty()) "0." else "." }
                    else buf += k
                }
            }
        }
    }
    val value = evalExpression(buf)
    val hasOperator = buf.drop(1).any { it in PAD_OPS }
    fun commit() {
        if (buf.isNotEmpty()) {
            var v = value ?: return
            if (!decimals) v = v.roundToLong().toDouble()
            if (min != null) v = maxOf(v, min)
            if (max != null) v = minOf(v, max)
            onResult(v)
        }
        onDismiss()
    }
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Color(0x22FFFFFF)).clickable { selected = true; if (buf.isEmpty()) buf = initial }.padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalAlignment = Alignment.End
                ) {
                    Text(
                        if (buf.isEmpty()) "\u2013" else buf, fontSize = if (buf.length > 12) 20.sp else 30.sp, maxLines = 1, softWrap = false, overflow = TextOverflow.Clip,
                        color = if (buf.isEmpty()) Color.Gray else Color.White,
                        modifier = if (selected && buf.isNotEmpty()) Modifier.background(primary.copy(alpha = 0.45f)) else Modifier
                    )
                    Text(if (hasOperator && value != null) "= " + formatNumber(value, decimals) else " ", fontSize = 13.sp, color = Color(0xFFB8B8C4), maxLines = 1)
                }
                val rows = listOf(
                    listOf("C" to 1f, "\u232B" to 1f, "\u00F7" to 1f, "\u00D7" to 1f),
                    listOf("7" to 1f, "8" to 1f, "9" to 1f, "\u2212" to 1f),
                    listOf("4" to 1f, "5" to 1f, "6" to 1f, "+" to 1f),
                    listOf("1" to 1f, "2" to 1f, "3" to 1f, "\u00B1" to 1f),
                    listOf("0" to 2f, "." to 1f, "=" to 1f)
                )
                for (row in rows) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for ((k, w) in row) OutlinedButton(
                        { key(k) }, Modifier.weight(w).height(46.dp), enabled = !(k == "." && !decimals), contentPadding = PaddingValues(0.dp)
                    ) { Text(k, fontSize = 18.sp, color = if (k in PAD_OPS.map { it.toString() } || k == "=") primary else Color.Unspecified) }
                }
            }
        },
        confirmButton = { TextButton({ commit() }, enabled = buf.isEmpty() || value != null) { Text("OK") } },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } }
    )
}

/**
 * A number field that opens the keypad (the Android keyboard is never used for numbers). Looks like the outlined text fields; [value] is shown as text and
 * [onValueChange] gets the new value as text (whole numbers without a point). The old value is selected when the pad opens, so the first digit replaces it.
 */
@Composable
fun NumberField(
    value: String, onValueChange: (String) -> Unit, modifier: Modifier = Modifier, label: String? = null,
    decimals: Boolean = true, min: Double? = null, max: Double? = null
) {
    var open by remember { mutableStateOf(false) }
    Column(
        modifier.heightIn(min = 48.dp).clip(RoundedCornerShape(6.dp)).border(1.dp, Color(0x80FFFFFF), RoundedCornerShape(6.dp)).clickable { open = true }.padding(horizontal = 12.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.Center
    ) {
        if (label != null) Text(label, fontSize = 11.sp, color = Color(0xFFB8B8C4), maxLines = 1)
        Text(value.ifEmpty { "\u2013" }, fontSize = 16.sp, maxLines = 1)
    }
    if (open) NumberPadDialog(label ?: "Value", value, decimals, min, max, { onValueChange(formatNumber(it, decimals)) }, { open = false })
}
