/*
 * Copyright 2020 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package androidx.room.compiler.processing.ksp

import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.KSPropertyDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.KSValueParameter

/**
 * Returns the type of a property as if it is member of the given [ksType].
 */
internal fun KSPropertyDeclaration.typeAsMemberOf(resolver: Resolver, ksType: KSType): KSType {
    return resolver.asMemberOf(
        property = this,
        containing = ksType
    )
}

internal fun KSValueParameter.typeAsMemberOf(
    resolver: Resolver,
    functionDeclaration: KSFunctionDeclaration,
    ksType: KSType
): KSType {
    val asMember = resolver.asMemberOf(
        function = functionDeclaration,
        containing = ksType
    )
    // TODO b/173224718
    // this is counter intuitive, we should remove asMemberOf from method parameters.
    val myIndex = functionDeclaration.parameters.indexOf(this)
    return asMember.parameterTypes[myIndex] ?: type.resolve()
}

internal fun KSFunctionDeclaration.returnTypeAsMemberOf(
    resolver: Resolver,
    ksType: KSType
): KSType {
    return resolver.asMemberOf(
        function = this,
        containing = ksType
    ).returnType ?: returnType?.resolve() ?: error("cannot find return type for $this")
}
