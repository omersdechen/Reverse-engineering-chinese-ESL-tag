package com.omer.esl

import android.app.Activity
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import java.io.File

class EditorActivity : Activity() {
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val f = File(Runner.scriptsDir(this), "main.py")
        val et = EditText(this).apply {
            setText(f.readText()); typeface = Typeface.MONOSPACE; textSize = 12f; gravity = Gravity.TOP
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setHorizontallyScrolling(true)
        }
        val save = Button(this).apply { text = "Save"; setOnClickListener { f.writeText(et.text.toString()); finish() } }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(save)
            addView(et, LinearLayout.LayoutParams(-1, 0, 1f))
        })
    }
}
