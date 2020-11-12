/*
 * Copyright 2010-2019 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.kapt3.base.incremental

import com.sun.tools.javac.code.Symbol
import org.jetbrains.kotlin.kapt3.base.util.KaptLogger
import java.io.File
import java.net.URI
import javax.annotation.processing.Filer
import javax.annotation.processing.ProcessingEnvironment
import javax.annotation.processing.Processor
import javax.annotation.processing.RoundEnvironment
import javax.lang.model.element.Element
import javax.lang.model.element.PackageElement
import javax.lang.model.element.TypeElement
import javax.tools.FileObject
import javax.tools.JavaFileManager
import javax.tools.JavaFileObject

private val ALLOWED_RUNTIME_TYPES = setOf(RuntimeProcType.AGGREGATING.name, RuntimeProcType.ISOLATING.name)

class IncrementalProcessor(private val processor: Processor, private val kind: DeclaredProcType, private val logger: KaptLogger) :
    Processor by processor {

    private var dependencyCollector = lazy { createDependencyCollector() }

    val processorName: String = processor.javaClass.name
    val incrementalSupportType: String = kind.name

    override fun init(processingEnv: ProcessingEnvironment) {
        if (!kind.canRunIncrementally) {
            processor.init(processingEnv)
        } else {
            val originalFiler = processingEnv.filer
            val incrementalFiler = IncrementalFiler(originalFiler)
            val incProcEnvironment = IncrementalProcessingEnvironment(processingEnv, incrementalFiler)
            processor.init(incProcEnvironment)
            incrementalFiler.dependencyCollector = dependencyCollector.value
        }
    }

    /** This has to invoked only once the processors has been initialized, because this accesses Processor.getSupportedOptions(). */
    private fun createDependencyCollector(): AnnotationProcessorDependencyCollector {
        val type = if (kind == DeclaredProcType.DYNAMIC) {
            val fromOptions = supportedOptions.singleOrNull { it.startsWith("org.gradle.annotation.processing.") }
            if (fromOptions == null) {
                RuntimeProcType.NON_INCREMENTAL
            } else {
                val declaredType = fromOptions.drop("org.gradle.annotation.processing.".length).toUpperCase()
                if (ALLOWED_RUNTIME_TYPES.contains(declaredType)) {
                    enumValueOf(declaredType)
                } else {
                    RuntimeProcType.NON_INCREMENTAL
                }
            }
        } else {
            kind.toRuntimeType()
        }

        return AnnotationProcessorDependencyCollector(type) { s -> logger.warn("Issue detected with $processorName. $s") }
    }

    fun isMissingIncrementalSupport(): Boolean {
        if (kind == DeclaredProcType.NON_INCREMENTAL) return true

        return kind == DeclaredProcType.DYNAMIC && getRuntimeType() == RuntimeProcType.NON_INCREMENTAL
    }

    fun isUnableToRunIncrementally() = !kind.canRunIncrementally
    fun getGeneratedToSources() = dependencyCollector.value.getGeneratedToSources()
    fun getAggregatedTypes() = dependencyCollector.value.getAggregatedTypes()
    fun getRuntimeType(): RuntimeProcType = dependencyCollector.value.getRuntimeType()


    override fun process(annotations: Set<TypeElement>, roundEnv: RoundEnvironment): Boolean {
        if (getRuntimeType() == RuntimeProcType.AGGREGATING) {
            dependencyCollector.value.recordProcessingInputs(processor.supportedAnnotationTypes, annotations, roundEnv)
        }
        return processor.process(annotations, roundEnv)
    }
}

internal class IncrementalProcessingEnvironment(private val processingEnv: ProcessingEnvironment, private val incFiler: IncrementalFiler) :
    ProcessingEnvironment by processingEnv {
    override fun getFiler(): Filer = incFiler
}

internal class IncrementalFiler(private val filer: Filer) : Filer by filer {

    internal var dependencyCollector: AnnotationProcessorDependencyCollector? = null

    override fun createSourceFile(name: CharSequence?, vararg originatingElements: Element?): JavaFileObject {
        val createdSourceFile = filer.createSourceFile(name, *originatingElements)
        dependencyCollector!!.add(createdSourceFile.toUri(), originatingElements)
        return createdSourceFile
    }

    override fun createClassFile(name: CharSequence?, vararg originatingElements: Element?): JavaFileObject {
        val createdClassFile = filer.createClassFile(name, *originatingElements)
        dependencyCollector!!.add(createdClassFile.toUri(), originatingElements)
        return createdClassFile
    }

    override fun createResource(
        location: JavaFileManager.Location?,
        pkg: CharSequence?,
        relativeName: CharSequence?,
        vararg originatingElements: Element?
    ): FileObject {
        val createdResource = filer.createResource(location, pkg, relativeName, *originatingElements)
        dependencyCollector!!.add(createdResource.toUri(), originatingElements)

        return createdResource
    }
}

internal class AnnotationProcessorDependencyCollector(
    private val runtimeProcType: RuntimeProcType,
    private val warningCollector: (String) -> Unit
) {
    private val generatedToSource = mutableMapOf<File, File?>()
    private val aggregatedTypes = mutableSetOf<String>()

    private var isFullRebuild = !runtimeProcType.isIncremental

    internal fun recordProcessingInputs(supportedAnnotationTypes: Set<String>, annotations: Set<TypeElement>, roundEnv: RoundEnvironment) {
        if (isFullRebuild) return

        if (supportedAnnotationTypes.contains("*")) {
            aggregatedTypes.addAll(getTopLevelClassNames(roundEnv.rootElements?.filterNotNull() ?: emptyList()))
        } else {
            for (annotation in annotations) {
                aggregatedTypes.addAll(
                    getTopLevelClassNames(
                        roundEnv.getElementsAnnotatedWith(
                            annotation
                        )?.filterNotNull() ?: emptyList()
                    )
                )
            }
        }
    }

    internal fun add(createdFile: URI, originatingElements: Array<out Element?>, classId: String?) {
        if (isFullRebuild) return

        val generatedFile = File(createdFile)
        if (runtimeProcType == RuntimeProcType.AGGREGATING) {
            generatedToSource[generatedFile] = null
        } else {
            val srcFiles = getSrcFiles(originatingElements)
            if (srcFiles.size != 1) {
                isFullRebuild = true
                warningCollector.invoke(
                    "Expected 1 originating source file when generating $generatedFile, " +
                            "but detected ${srcFiles.size}: [${srcFiles.joinToString()}]."
                )
            } else {
                generatedToSource[generatedFile] = srcFiles.single()
            }
        }
    }

    internal fun getGeneratedToSources(): Map<File, File?> = if (isFullRebuild) emptyMap() else generatedToSource

    internal fun getAggregatedTypes(): Set<String> = if (isFullRebuild) emptySet() else aggregatedTypes

    internal fun getRuntimeType(): RuntimeProcType {
        return if (isFullRebuild) {
            RuntimeProcType.NON_INCREMENTAL
        } else {
            runtimeProcType
        }
    }
}

private fun getSrcFiles(elements: Array<out Element?>): Set<File> {
    return elements.filterNotNull().mapNotNull { elem ->
        var origin = elem
        while (origin.enclosingElement != null && origin.enclosingElement !is PackageElement) {
            origin = origin.enclosingElement
        }
        val uri = (origin as? Symbol.ClassSymbol)?.sourcefile?.toUri()?.takeIf { it.isAbsolute }
        uri?.let { File(it).canonicalFile }
    }.toSet()
}

private const val PACKAGE_TYPE_NAME = "package-info"

fun getElementName(current: Element?): String? {
    if (current is PackageElement) {
        val packageName = current.qualifiedName.toString()
        return if (packageName.isEmpty()) {
            PACKAGE_TYPE_NAME
        } else {
            "$packageName.$PACKAGE_TYPE_NAME"
        }
    }
    if (current is TypeElement) {
        return current.qualifiedName.toString()
    }
    return null
}

private fun getTopLevelClassNames(elements: Collection<Element>): Collection<String> {
    return elements.mapNotNull { elem ->
        var origin = elem
        while (origin.enclosingElement != null && origin.enclosingElement !is PackageElement) {
            origin = origin.enclosingElement
        }
        getElementName(origin)
    }
}

enum class DeclaredProcType(val canRunIncrementally: Boolean) {
    AGGREGATING(true) {
        override fun toRuntimeType() = RuntimeProcType.AGGREGATING
    },
    ISOLATING(true) {
        override fun toRuntimeType() = RuntimeProcType.ISOLATING
    },
    DYNAMIC(true) {
        override fun toRuntimeType() = throw IllegalStateException("This should not be used")
    },
    NON_INCREMENTAL(false) {
        override fun toRuntimeType() = RuntimeProcType.NON_INCREMENTAL
    },
    INCREMENTAL_BUT_OTHER_APS_ARE_NOT(false) {
        override fun toRuntimeType() = RuntimeProcType.NON_INCREMENTAL
    },
    ;

    abstract fun toRuntimeType(): RuntimeProcType
}

enum class RuntimeProcType(val isIncremental: Boolean) {
    AGGREGATING(true),
    ISOLATING(true),
    NON_INCREMENTAL(false),
}