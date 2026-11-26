import java.nio.file.{Files, Paths}

import dotty.tools.dotc.Driver
import dotty.tools.dotc.interfaces.Diagnostic.ERROR
import dotty.tools.dotc.reporting.{Diagnostic, StoreReporter}
import scala.jdk.CollectionConverters._

/* Compiles every job of a job file in one JVM and prints one result line per job:
 *   <id>\t<errorCount>\t<sorted file:line of errors>\t<crash message, if any>
 * Job line format: <id>\t<space separated options>\t<space separated files>
 */
object CCDriver {
  def main(args: Array[String]): Unit = {
    val jobs = Files.readAllLines(Paths.get(args(0))).asScala.filter(_.nonEmpty)
    val libcp = args(1)
    val out = new java.io.PrintWriter(args(2))
    for (job <- jobs) {
      val Array(id, opts, files) = job.split("\t", -1)
      val reporter = new StoreReporter(null)
      val cmd = Array(
        "-usejavacp",
        "-classpath",
        libcp,
        "-Ystop-after:cc",
        "-color:never"
      ) ++
        opts.split(" ").filter(_.nonEmpty) ++ files
          .split(" ")
          .filter(_.nonEmpty)
      val crash =
        try { new Driver().process(cmd, reporter); "" }
        catch {
          case t: Throwable =>
            ("CRASH " + t.toString)
              .replace('\t', ' ')
              .replace('\n', ' ')
              .take(300)
        }
      val errs =
        reporter.removeBufferedMessages(using null).filter(_.level == ERROR)
      val locs = errs.map { d =>
        val p = d.pos
        if (p.exists) s"${p.source.file.name}:${p.line + 1}" else "?"
      }.sorted
      out.println(s"$id\t${errs.size}\t${locs.mkString(",")}\t$crash")
      out.flush()
    }
    out.close()
  }
}
