package protein.tracking

import com.intellij.openapi.ui.DialogWrapper
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTextArea

class DialogErrorTracking : ErrorTracking {
    override fun logException(throwable: Throwable) {
        ErrorDialog(throwable).show()
    }
}

class ErrorDialog(val throwable: Throwable) : DialogWrapper(true) {
    init {
        init()
        title = "Error"
    }

    override fun createCenterPanel(): JComponent? {
        val panel = JPanel(BorderLayout())
        val label = JTextArea(throwable.stackTrace.joinToString { it.toString() + "\n" })
        label.preferredSize = Dimension(100, 100)
        panel.add(label, BorderLayout.CENTER)
        return panel
    }
}