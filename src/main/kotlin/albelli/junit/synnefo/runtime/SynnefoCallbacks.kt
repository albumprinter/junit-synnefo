package albelli.junit.synnefo.runtime

import albelli.junit.synnefo.api.SynnefoAfterAll
import albelli.junit.synnefo.api.SynnefoBeforeAll
import albelli.junit.synnefo.runtime.exceptions.SynnefoException
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.*

class SynnefoCallbacks(private val clazz: Class<*>) {

    fun beforeAll() {
        getCallbacksForAnnotation(SynnefoBeforeAll::class.java).stream()
                .sorted(Comparator.comparingInt { it.getAnnotation(SynnefoBeforeAll::class.java).order })
                .forEachOrdered { invokeCallback(it) }
    }

    fun afterAll() {
        getCallbacksForAnnotation(SynnefoAfterAll::class.java).stream()
                .sorted(Comparator.comparingInt { it.getAnnotation(SynnefoAfterAll::class.java).order })
                .forEachOrdered { invokeCallback(it) }
    }

    private fun getCallbacksForAnnotation(annotation: Class<out Annotation>): List<Method> {
        return clazz.methods
                .filter {
                    (Modifier.isStatic(it.modifiers)
                            && it.isAnnotationPresent(annotation)
                            && it.parameterCount == 0)
                }
    }

    private fun invokeCallback(callback: Method, vararg args: Any) {
        try {
            callback.invoke(null, *args)
        } catch (e: IllegalAccessException) {
            throw SynnefoException(e)
        } catch (e: InvocationTargetException) {
            throw SynnefoException(e)
        }

    }
}
