/**
 * Copyright (C) 2010-2011, FuseSource Corp.  All rights reserved.
 *
 *     http://fusesource.com
 *
 * The software in this package is published under the terms of the
 * CDDL license a copy of which has been included with this distribution
 * in the license.txt file.
 */

package org.fusesource.fabric.launcher.internal

import java.io._
import org.fusesource.hawtdispatch._
import java.util.concurrent.{ThreadFactory, LinkedBlockingQueue, TimeUnit, ThreadPoolExecutor}
import java.util.Properties
import java.util.regex.{Matcher, Pattern}

object IOSupport {

  def read_bytes(in:InputStream) = {
    val out = new ByteArrayOutputStream()
    copy(in, out)
    out.toByteArray
  }

  /**
   * Returns the number of bytes copied.
   */
  def copy(in: InputStream, out: OutputStream): Long = {
    var bytesCopied: Long = 0
    val buffer = new Array[Byte](8192)
    var bytes = in.read(buffer)
    while (bytes >= 0) {
      out.write(buffer, 0, bytes)
      bytesCopied += bytes
      bytes = in.read(buffer)
    }
    bytesCopied
  }

  def using[R, C <: Closeable](closable: C)(proc: C => R) = {
    try {
      proc(closable)
    } finally {
      try {
        closable.close
      } catch {
        case ignore =>
      }
    }
  }

}

object FileSupport {
  import IOSupport._

  implicit def to_rich_file(file: File): RichFile = new RichFile(file)

  val file_separator = System.getProperty("file.separator")

  def fix_file_separator(command:String) = command.replaceAll("""/|\\""", Matcher.quoteReplacement(file_separator))

  case class RichFile(self: File) {

    def /(path: String) = new File(self, path)

    def copy_to(target: File) = {
      using(new FileOutputStream(target)) { os =>
        using(new FileInputStream(self)) { is =>
          IOSupport.copy(is, os)
        }
      }
    }

    def recursive_list: List[File] = {
      if (self.isDirectory) {
        self :: self.listFiles.toList.flatten(_.recursive_list)
      } else {
        self :: Nil
      }
    }

    def recursive_delete: Unit = {
      if (self.exists) {
        if (self.isDirectory) {
          self.listFiles.foreach(_.recursive_delete)
        }
        self.delete
      }
    }

    def recursive_copy_to(target: File): Unit = {
      if (self.isDirectory) {
        target.mkdirs
        self.listFiles.foreach(file => file.recursive_copy_to(target / file.getName))
      } else {
        self.copy_to(target)
      }
    }

    def read: Array[Byte] = {
      using(new FileInputStream(self)) { in =>
        read_bytes(in)
      }
    }

    def read_text(charset: String = "UTF-8"): String = new String(this.read, charset)

  }

}

object ThreadSupport {

  val POOL_SIZE = Integer.parseInt(System.getProperty("launcher.thread.pool", "16"))
  val BLOCKING_POOL = new ThreadPoolExecutor(POOL_SIZE, POOL_SIZE, 30, TimeUnit.SECONDS, new LinkedBlockingQueue[Runnable](), new ThreadFactory() {
    def newThread(r: Runnable) = {
      val rc = new Thread(r, "blocking task")
      rc.setDaemon(true)
      rc
    }
  })
  BLOCKING_POOL.allowCoreThreadTimeOut(true)

}

object ProcessSupport {
  import ThreadSupport._
  import IOSupport._

  implicit def to_rich_process_builder(self: ProcessBuilder): RichProcessBuilder = new RichProcessBuilder(self)

  case class RichProcessBuilder(self: ProcessBuilder) {

    def start(out: OutputStream = null, err: OutputStream = null, in: InputStream = null) = {
      self.redirectErrorStream(out == err)
      val process = self.start
      if (in != null) {
        BLOCKING_POOL {
          try {
            using(process.getOutputStream) { out =>
              IOSupport.copy(in, out)
            }
          } catch {
            case _ =>
          }
        }
      } else {
        process.getOutputStream.close
      }

      if (out != null) {
        BLOCKING_POOL {
          try {
            using(process.getInputStream) { in =>
              IOSupport.copy(in, out)
            }
          } catch {
            case _ =>
          }
        }
      } else {
        process.getInputStream.close
      }

      if (err != null && err != out) {
        BLOCKING_POOL {
          try {
            using(process.getErrorStream) { in =>
              IOSupport.copy(in, err)
            }
          } catch {
            case _ =>
          }
        }
      } else {
        process.getErrorStream.close
      }
      process
    }

  }

  implicit def to_rich_process(self: Process): RichProcess = new RichProcess(self)

  case class RichProcess(self: Process) {
    def on_exit(func: (Int) => Unit) = BLOCKING_POOL {
      self.waitFor
      func(self.exitValue)
    }
  }

  implicit def to_process_builder(args: Seq[String]): ProcessBuilder = new ProcessBuilder().command(args: _*)

  def launch(command: String*)(func: (Int, Array[Byte], Array[Byte]) => Unit): Unit = launch(command)(func)

  def launch(p: ProcessBuilder, in: InputStream = null)(func: (Int, Array[Byte], Array[Byte]) => Unit): Unit = {
    val out = new ByteArrayOutputStream
    val err = new ByteArrayOutputStream
    p.start(out, err, in).on_exit {
      code =>
        func(code, out.toByteArray, err.toByteArray)
    }
  }

  def system(command: String*): (Int, Array[Byte], Array[Byte]) = system(command)

  def system(p: ProcessBuilder, in: InputStream = null): (Int, Array[Byte], Array[Byte]) = {
    val out = new ByteArrayOutputStream
    val err = new ByteArrayOutputStream
    val process = p.start(out, err, in)
    process.waitFor
    (process.exitValue, out.toByteArray, err.toByteArray)
  }
  
  val os_name = System.getProperty("os.name")
  def is_os_windows = os_name.toLowerCase.startsWith("windows")


}

object FilterSupport {

  private val pattern: Pattern = Pattern.compile("\\$\\{([^\\}]+)\\}")

  implicit def asScalaMap(props:Properties):Map[String,String] = {
    import collection.JavaConversions._
    Map[String,String](asScalaIterable(props.entrySet).toSeq.map(x=>(x.getKey.toString, x.getValue.toString)):_*)
  }

  def translate(value: String, translations:Map[String,String]): String = {
    var rc = new StringBuilder
    var remaining = value
    while( !remaining.isEmpty ) {
      if( !translations.find{ case (key,value) =>
        if( remaining.startsWith(key) ) {
          rc.append(value)
          remaining = remaining.stripPrefix(key)
          true
        } else {
          false
        }
      }.isDefined ) {
        rc.append(remaining.charAt(0))
        remaining = remaining.substring(1)
      }
    }
    rc.toString
  }

  def filter(value: String, props:Map[String,String]): String = {
    var rc = value
    var start: Int = 0
    var done = false
    while (!done) {
      var matcher: Matcher = pattern.matcher(rc)
      if( matcher.find(start) ) {
        var group = matcher.group(1)
        props.get(group) match {
          case Some(property)=>
            rc = matcher.replaceFirst(Matcher.quoteReplacement(property))
          case None =>
            start = matcher.end
        }
      } else {
        done = true
      }
    }
    rc
  }
}