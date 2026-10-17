// Harness shim: Android-only parts of androidx.compose.ui (ui 1.7.x, androidMain) that the Compose Multiplatform
// desktop jars used for type checking do not contain -- resource lookup (stringResource & co.).
@file:JvmName("ShimResourcesKt")
@file:Suppress("unused", "UNUSED_PARAMETER")

package androidx.compose.ui.res

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp

@Composable
@ReadOnlyComposable
fun stringResource(id: Int): String = throw UnsupportedOperationException("harness stub")

@Composable
@ReadOnlyComposable
fun stringResource(
    id: Int,
    vararg formatArgs: Any,
): String = throw UnsupportedOperationException("harness stub")

@Composable
@ReadOnlyComposable
fun pluralStringResource(
    id: Int,
    count: Int,
): String = throw UnsupportedOperationException("harness stub")

@Composable
@ReadOnlyComposable
fun pluralStringResource(
    id: Int,
    count: Int,
    vararg formatArgs: Any,
): String = throw UnsupportedOperationException("harness stub")

@Composable
@ReadOnlyComposable
fun stringArrayResource(id: Int): Array<String> = throw UnsupportedOperationException("harness stub")

@Composable
@ReadOnlyComposable
fun integerResource(id: Int): Int = throw UnsupportedOperationException("harness stub")

@Composable
@ReadOnlyComposable
fun integerArrayResource(id: Int): IntArray = throw UnsupportedOperationException("harness stub")

@Composable
@ReadOnlyComposable
fun booleanResource(id: Int): Boolean = throw UnsupportedOperationException("harness stub")

@Composable
@ReadOnlyComposable
fun colorResource(id: Int): Color = throw UnsupportedOperationException("harness stub")

@Composable
@ReadOnlyComposable
fun dimensionResource(id: Int): Dp = throw UnsupportedOperationException("harness stub")

@Composable
fun painterResource(id: Int): Painter = throw UnsupportedOperationException("harness stub")

@Composable
fun ImageBitmap.Companion.imageResource(id: Int): ImageBitmap = throw UnsupportedOperationException("harness stub")

@Composable
fun ImageVector.Companion.vectorResource(id: Int): ImageVector = throw UnsupportedOperationException("harness stub")
