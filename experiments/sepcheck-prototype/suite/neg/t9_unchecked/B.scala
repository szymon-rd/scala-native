import language.experimental.captureChecking
import language.experimental.separationChecking
def t(): Unit = { val a = Res("a"); val b = dup(a); free(b); free(a) } // error
