package esw.ocs.dsl.params

import csw.params.core.generics.Key
import csw.params.core.generics.KeyType
import csw.params.core.generics.Parameter
import csw.params.core.generics.ParameterSetType
import esw.ocs.dsl.nullable
import org.jooq.Param

/** ========== Parameter =========== **/
val <T> Parameter<T>.values: List<T> get() = jValues().toList()
val <T> Parameter<T>.first: T get() = head()

fun <T> Parameter<T>.kGet(index: Int): T? = jGet(index).nullable()
operator fun <T> Parameter<T>.invoke(index: Int): T = value(index)

/** ========== ParameterSetType =========== **/

fun <S> ParameterSetType<*>.kFind(parameter: Parameter<S>): Parameter<S>? = jFind(parameter).nullable()

fun <S> ParameterSetType<*>.kExists(key: Key<S>): Boolean = exists(key)

fun <S> ParameterSetType<*>.kGet(key: Key<S>): Parameter<S>? = jGet(key).nullable()
fun <S> ParameterSetType<*>.kGet(keyName: String, keyType: KeyType<S>): Parameter<S>? = jGet(keyName, keyType).nullable()
operator fun <S> ParameterSetType<*>.invoke(key: Key<S>): Parameter<S> = apply(key)

// following extensions will only work on concrete supertypes of ParameterSetType, for example, ObserveEvent/Setup command
// but not on paramType present in base types like Event/Command

fun <T : ParameterSetType<T>, P : Parameter<*>> T.kMadd(vararg parameters: P): T = jMadd(parameters.toSet())
fun <T : ParameterSetType<T>, S> T.kRemove(key: Key<S>): T = remove(key)
fun <T : ParameterSetType<T>, P : Parameter<*>> T.kRemove(parameter: P): T = remove(parameter)

val <T : ParameterSetType<T>> T.params: Params get() = Params(this.jParamSet())