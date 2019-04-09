package albelli.junit.synnefo.api

@Target(AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY_GETTER, AnnotationTarget.PROPERTY_SETTER)
annotation class SynnefoBeforeAll(val order: Int = 0)
