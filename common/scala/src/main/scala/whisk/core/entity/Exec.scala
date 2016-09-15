/*
 * Copyright 2015-2016 IBM Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package whisk.core.entity

import java.util.Base64

import scala.util.Try

import scala.language.postfixOps

import spray.json._
import whisk.core.entity.ArgNormalizer.trim
import whisk.core.entity.Attachments._
import whisk.core.entity.size.SizeInt
import whisk.core.entity.size.SizeString

/**
 * Exec encodes the executable details of an action. For black
 * box container, an image name is required. For Javascript and Python
 * actions, the code to execute is required.
 * For Swift actions, the source code to execute the action is required.
 * For Java actions, a base64-encoded string representing a jar file is
 * required, as well as the name of the entrypoint class.
 *
 * exec: { kind  : one of supported language runtimes
 *         code  : code to execute if kind is supported
 *         init  : optional zipfile reference when kind is "nodejs",
 *         image : container name when kind is "blackbox",
 *         main  : a fully-qualified class name when kind is "java" }
 */
sealed abstract class Exec(val kind: String) extends ByteSizeable {
    def image: String
    /** Indicates if the container generates log markers to stdout/stderr once action activation completes. */
    val sentinelledLogs = true
    override def toString = Exec.serdes.write(this).compactPrint
}

/**
 * A common super class for all action exec types that contain their executable
 * code explicitly.
 */
sealed abstract class CodeExec[T <% SizeConversion](kind: String) extends Exec(kind) {
    // The executable code
    val code: T

    // All codeexec containers have this in common that the image name is
    // fully determined by the kind.
    final val image = Exec.imagename(kind)

    // Whether the code is stored in a text-readable or binary format.
    def binary: Boolean = false

    def size = code.sizeInBytes
}

sealed abstract class NodeJSAbstractExec(kind: String) extends CodeExec[String](kind) {
    // The entrypoint, if not 'main'.
    val init: Option[String]
    override def size = super.size + init.map(_.sizeInBytes).getOrElse(0 B)
    override lazy val binary: Boolean = {
        val t = code.trim
        (t.length % 4 == 0) && Try(Exec.b64decoder.decode(t)).isSuccess
    }
}

protected[core] case class NodeJSExec(code: String, init: Option[String]) extends NodeJSAbstractExec(Exec.NODEJS)
protected[core] case class NodeJS6Exec(code: String, init: Option[String]) extends NodeJSAbstractExec(Exec.NODEJS6)

sealed abstract class SwiftAbstractExec(kind: String) extends CodeExec[String](kind)

protected[core] case class SwiftExec(code: String) extends SwiftAbstractExec(Exec.SWIFT)
protected[core] case class Swift3Exec(code: String) extends SwiftAbstractExec(Exec.SWIFT3)

protected[core] case class JavaExec(code: Attachment[String], main: String) extends CodeExec[Attachment[String]](Exec.JAVA) {
    override val binary = true
    override val sentinelledLogs = false
    override def size = super.size + main.sizeInBytes
}

protected[core] case class PythonExec(code: String) extends CodeExec[String](Exec.PYTHON)

protected[core] case class BlackBoxExec(image: String) extends Exec(Exec.BLACKBOX) {
    def size = image sizeInBytes
    override val sentinelledLogs = false
}

/**
 * add temporary field that holds the "fixed" names for components where the '_' is replaced by the user's namespace
 */
protected[core] case class SequenceExec(code: String, components: Vector[FullyQualifiedEntityName]) extends Exec(Exec.SEQUENCE) {
    val image = Exec.imagename(Exec.NODEJS)
    def size = 0.bytes // not used for the hacky implementation and not used for the new implementation either
}

protected[core] object Exec
    extends ArgNormalizer[Exec]
    with DefaultJsonProtocol
    with DefaultRuntimeVersions {

    protected[core] lazy val b64decoder = Base64.getDecoder()

    // The possible values of the JSON 'kind' field.
    protected[core] val NODEJS = "nodejs"
    protected[core] val NODEJS6 = "nodejs:6"
    protected[core] val SWIFT = "swift"
    protected[core] val SWIFT3 = "swift:3"
    protected[core] val JAVA = "java"
    protected[core] val PYTHON = "python"
    protected[core] val SEQUENCE = "sequence"
    protected[core] val BLACKBOX = "blackbox"
    protected[core] val runtimes = Set(NODEJS, NODEJS6, SWIFT, SWIFT3, JAVA, PYTHON, SEQUENCE, BLACKBOX)

    // Constructs standard image name for action
    protected[core] def imagename(name: String) = s"${name}action".replace(":", "")

    val sizeLimit = 48 MB

    protected[core] def js(code: String, init: String = null): Exec = NodeJSExec(trim(code), Option(init).map(_.trim))
    protected[core] def js6(code: String, init: String = null): Exec = NodeJS6Exec(trim(code), Option(init).map(_.trim))
    protected[core] def bb(image: String): Exec = BlackBoxExec(trim(image))
    protected[core] def swift(code: String): Exec = SwiftExec(trim(code))
    protected[core] def swift3(code: String): Exec = Swift3Exec(trim(code))
    protected[core] def java(jar: String, main: String): Exec = JavaExec(Inline(trim(jar)), trim(main))
    protected[core] def sequence(components: Vector[String]): Exec = SequenceExec(Pipecode.code, components map {c => FullyQualifiedEntityName(c) } )

    private def attFmt[T: JsonFormat] = Attachments.serdes[T]

    override protected[core] implicit val serdes = new RootJsonFormat[Exec] {
        override def write(e: Exec) = e match {
            case n: NodeJSAbstractExec =>
                val base = Map("kind" -> JsString(n.kind), "code" -> JsString(n.code), "binary" -> JsBoolean(n.binary))
                n.init.map(i => JsObject(base + ("init" -> JsString(i)))).getOrElse(JsObject(base))

            case s: SwiftAbstractExec =>
                JsObject("kind" -> JsString(s.kind), "code" -> JsString(s.code), "binary" -> JsBoolean(s.binary))

            case j @ JavaExec(jar, main)  => JsObject("kind" -> JsString(Exec.JAVA), "jar" -> attFmt[String].write(jar), "main" -> JsString(main), "binary" -> JsBoolean(j.binary))

            case p @ PythonExec(code)     => JsObject("kind" -> JsString(Exec.PYTHON), "code" -> JsString(code), "binary" -> JsBoolean(p.binary))

            case SequenceExec(code, comp) => JsObject("kind" -> JsString(Exec.SEQUENCE), "code" -> JsString(code), "components" -> JsArray(comp map { c => JsString(c.toString) }))
            case BlackBoxExec(image)      => JsObject("kind" -> JsString(Exec.BLACKBOX), "image" -> JsString(image))
        }

        override def read(v: JsValue) = {
            require(v != null)

            val obj = v.asJsObject

            val kindField = obj.getFields("kind") match {
                case Seq(JsString(k)) => k.trim.toLowerCase
                case _                => throw new DeserializationException("'kind' must be a string defined in 'exec'")
            }

            // map "default" virtual runtime versions to the currently blessed actual runtime version
            val kind = resolveDefaultRuntime(kindField)

            kind match {
                case Exec.NODEJS | Exec.NODEJS6 =>
                    val code: String = obj.getFields("code") match {
                        case Seq(JsString(c)) => c
                        case _                => throw new DeserializationException(s"'code' must be a string defined in 'exec' for '$kind' actions")
                    }
                    val init: Option[String] = obj.getFields("init") match {
                        case Seq(JsString(i)) => Some(i)
                        case Seq(_)           => throw new DeserializationException(s"if defined, 'init' must a string in 'exec' for '$kind' actions")
                        case _                => None
                    }
                    if (kind == Exec.NODEJS) NodeJSExec(code, init) else NodeJS6Exec(code, init)

                case Exec.SEQUENCE =>
                    val comp: Vector[FullyQualifiedEntityName] = obj.getFields("components") match {
                        case Seq(JsArray(components)) =>
                            components map {
                                _ match {
                                    case JsString(s) => FullyQualifiedEntityName(s)
                                    case _           => throw new DeserializationException(s"'components' must be an array of strings")
                                }
                            }
                        case Seq(_) => throw new DeserializationException(s"'components' must be an array")
                        case _      => throw new DeserializationException(s"'components' must be defined for sequence kind")
                    }
                    SequenceExec(Pipecode.code, comp)

                case Exec.SWIFT | Exec.SWIFT3 =>
                    val code: String = obj.getFields("code") match {
                        case Seq(JsString(c)) => c
                        case _                => throw new DeserializationException(s"'code' must be a string defined in 'exec' for '$kind' actions")
                    }
                    if (kind == Exec.SWIFT) SwiftExec(code) else Swift3Exec(code)

                case Exec.JAVA =>
                    val jar: Attachment[String] = obj.fields.get("jar").map { f =>
                        attFmt[String].read(f)
                    } getOrElse {
                        throw new DeserializationException(s"'jar' must be a valid base64 string in 'exec' for '${Exec.JAVA}' actions")
                    }
                    val main: String = obj.getFields("main") match {
                        case Seq(JsString(m)) => m
                        case _                => throw new DeserializationException(s"'main' must be a string defined in 'exec' for '${Exec.JAVA}' actions")
                    }
                    JavaExec(jar, main)

                case Exec.PYTHON =>
                    val code: String = obj.getFields("code") match {
                        case Seq(JsString(c)) => c
                        case _                => throw new DeserializationException(s"'code' must be a string defined in 'exec' for '${Exec.PYTHON}' actions")
                    }
                    PythonExec(code)

                case Exec.BLACKBOX =>
                    val image: String = obj.getFields("image") match {
                        case Seq(JsString(i)) => i
                        case _                => throw new DeserializationException(s"'image' must be a string defined in 'exec' for '${Exec.BLACKBOX}' actions")
                    }
                    BlackBoxExec(image)

                case _ => throw new DeserializationException(s"kind '$kind' not in $runtimes")
            }
        }
    }
}
