/*
 * Copyright (c) 2026 @Natfii. All rights reserved.
 */

@file:Suppress("MagicNumber", "MatchingDeclarationName")

package com.zeroclaw.android.ui.component

import android.widget.ImageView
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.vectordrawable.graphics.drawable.AnimatedVectorDrawableCompat
import com.zeroclaw.android.R
import com.zeroclaw.android.util.LocalPowerSaveMode

/** Motion/pose variants for the mini Zero mascot. */
enum class MiniZeroMascotState {
    /** Static friendly pose for welcome surfaces and quiet idle states. */
    Idle,

    /** Slightly more active pose for in-progress model thinking states. */
    Thinking,

    /** Quick compact motion used for terminal typing or active tool output. */
    Typing,

    /** Calm positive confirmation pose for successful completions. */
    Success,

    /** Gentle wobble used for recoverable errors or blocked actions. */
    Error,

    /** A brighter bounce used for celebratory or success moments. */
    Celebrate,

    /** A subtle side-to-side peek used for compact ambient UI placements. */
    Peek,

    /** Gentle floating with happy eyes for warm, content moments. */
    Smiling,

    /** Bouncing with heart eyes for affectionate or enthusiastic reactions. */
    Love,

    /** Rapid shaking with slanted eyes for frustrated or displeased states. */
    Angry,

    /** Slow breathing with closed eyes for long-running tasks or deep idle. */
    Sleeping,
}

/**
 * Renders the mini Zero mascot using native [AnimatedVectorDrawableCompat]
 * for eye animations and Compose [graphicsLayer] transforms for body motion.
 *
 * Using native AVD instead of Compose's [androidx.compose.animation.graphics]
 * allows infinite-repeat animators (eye blink, eye recall) that Compose's
 * [rememberAnimatedVectorPainter] does not support.
 *
 * When [LocalPowerSaveMode] is active, the static vector is shown with no
 * animation.
 *
 * @param state Motion variant to display.
 * @param modifier Modifier applied to the mascot container.
 * @param size Target render size for the mascot.
 * @param animated Whether ambient motion is enabled when power save is off.
 * @param motionScale Multiplier applied to ambient motion transforms.
 * @param contentDescription Accessibility description. Pass `null` when decorative.
 */
@Composable
fun MiniZeroMascot(
    state: MiniZeroMascotState,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    animated: Boolean = true,
    motionScale: Float = 1f,
    contentDescription: String? = null,
) {
    val isPowerSave = LocalPowerSaveMode.current
    val shouldAnimate = animated && !isPowerSave
    val effectiveMotionScale = motionScale.coerceAtLeast(0f)
    val transition =
        if (shouldAnimate) {
            rememberInfiniteTransition(label = "mini_zero_mascot")
        } else {
            null
        }

    val verticalShift by animateOrDefault(
        transition,
        translationYTarget(state) * effectiveMotionScale,
        motionDuration(state),
    )
    val horizontalShift by animateOrDefault(
        transition,
        translationXTarget(state) * effectiveMotionScale,
        motionDuration(state),
        symmetric = true,
    )
    val tilt by animateOrDefault(
        transition,
        tiltTarget(state) * effectiveMotionScale,
        motionDuration(state),
        symmetric = true,
    )
    val scale by animateOrDefault(
        transition,
        scaleTarget(state),
        motionDuration(state),
        initialValue = 1f,
    )

    val avdResId = animatedVectorResource(state)
    val staticResId = staticVectorResource(state)
    val semanticsModifier =
        if (contentDescription != null) {
            Modifier.semantics {
                this.contentDescription = contentDescription
            }
        } else {
            Modifier
        }

    AndroidView(
        factory = { context ->
            ImageView(context).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                importantForAccessibility =
                    if (contentDescription != null) {
                        ImageView.IMPORTANT_FOR_ACCESSIBILITY_YES
                    } else {
                        ImageView.IMPORTANT_FOR_ACCESSIBILITY_NO
                    }
            }
        },
        update = { imageView ->
            val currentTag = imageView.tag
            if (shouldAnimate) {
                if (currentTag != avdResId) {
                    val avd = AnimatedVectorDrawableCompat.create(imageView.context, avdResId)
                    imageView.setImageDrawable(avd)
                    imageView.tag = avdResId
                    avd?.start()
                }
            } else {
                if (currentTag != staticResId) {
                    imageView.setImageResource(staticResId)
                    imageView.tag = staticResId
                }
            }
            imageView.contentDescription = contentDescription
        },
        modifier =
            modifier
                .size(size)
                .then(semanticsModifier)
                .graphicsLayer {
                    translationX = horizontalShift
                    translationY = verticalShift
                    rotationZ = tilt
                    scaleX = scale
                    scaleY = scale
                },
    )
}

/**
 * Animates a float value with an infinite transition, or returns a
 * static default when no transition is available (power save mode).
 */
@Composable
private fun animateOrDefault(
    transition: androidx.compose.animation.core.InfiniteTransition?,
    target: Float,
    durationMs: Int,
    symmetric: Boolean = false,
    initialValue: Float = 0f,
): androidx.compose.runtime.State<Float> {
    val from = if (symmetric) -target else initialValue
    return if (transition != null) {
        transition.animateFloat(
            initialValue = from,
            targetValue = target,
            animationSpec =
                infiniteRepeatable(
                    animation = tween(durationMillis = durationMs),
                    repeatMode = RepeatMode.Reverse,
                ),
            label = "motion",
        )
    } else {
        androidx.compose.runtime.mutableFloatStateOf(initialValue)
    }
}

/** Returns the horizontal travel in pixels for the given mascot [state]. */
private fun translationXTarget(state: MiniZeroMascotState): Float =
    when (state) {
        MiniZeroMascotState.Idle -> 0f
        MiniZeroMascotState.Thinking -> 1.5f
        MiniZeroMascotState.Typing -> 3f
        MiniZeroMascotState.Success -> 1f
        MiniZeroMascotState.Error -> 3.5f
        MiniZeroMascotState.Celebrate -> 2f
        MiniZeroMascotState.Peek -> 4f
        MiniZeroMascotState.Smiling -> 0.5f
        MiniZeroMascotState.Love -> 1.5f
        MiniZeroMascotState.Angry -> 3f
        MiniZeroMascotState.Sleeping -> 0f
    }

/** Returns the vertical travel in pixels for the given mascot [state]. */
private fun translationYTarget(state: MiniZeroMascotState): Float =
    when (state) {
        MiniZeroMascotState.Idle -> -2f
        MiniZeroMascotState.Thinking -> -5f
        MiniZeroMascotState.Typing -> -2f
        MiniZeroMascotState.Success -> -4f
        MiniZeroMascotState.Error -> -1f
        MiniZeroMascotState.Celebrate -> -7f
        MiniZeroMascotState.Peek -> -2f
        MiniZeroMascotState.Smiling -> -3.5f
        MiniZeroMascotState.Love -> -6f
        MiniZeroMascotState.Angry -> -1.5f
        MiniZeroMascotState.Sleeping -> -1f
    }

/** Returns the maximum tilt angle in degrees for the given mascot [state]. */
private fun tiltTarget(state: MiniZeroMascotState): Float =
    when (state) {
        MiniZeroMascotState.Idle -> 1.5f
        MiniZeroMascotState.Thinking -> 3f
        MiniZeroMascotState.Typing -> 2f
        MiniZeroMascotState.Success -> 2.5f
        MiniZeroMascotState.Error -> 5f
        MiniZeroMascotState.Celebrate -> 4f
        MiniZeroMascotState.Peek -> 5f
        MiniZeroMascotState.Smiling -> 2f
        MiniZeroMascotState.Love -> 3.5f
        MiniZeroMascotState.Angry -> 4.5f
        MiniZeroMascotState.Sleeping -> 0.5f
    }

/** Returns the peak scale multiplier for the given mascot [state]. */
private fun scaleTarget(state: MiniZeroMascotState): Float =
    when (state) {
        MiniZeroMascotState.Idle -> 1.01f
        MiniZeroMascotState.Thinking -> 1.02f
        MiniZeroMascotState.Typing -> 1.015f
        MiniZeroMascotState.Success -> 1.03f
        MiniZeroMascotState.Error -> 0.99f
        MiniZeroMascotState.Celebrate -> 1.04f
        MiniZeroMascotState.Peek -> 1.01f
        MiniZeroMascotState.Smiling -> 1.02f
        MiniZeroMascotState.Love -> 1.03f
        MiniZeroMascotState.Angry -> 0.99f
        MiniZeroMascotState.Sleeping -> 0.97f
    }

/** Returns the motion duration in milliseconds for the given mascot [state]. */
private fun motionDuration(state: MiniZeroMascotState): Int =
    when (state) {
        MiniZeroMascotState.Idle -> 2400
        MiniZeroMascotState.Thinking -> 1200
        MiniZeroMascotState.Typing -> 700
        MiniZeroMascotState.Success -> 1000
        MiniZeroMascotState.Error -> 800
        MiniZeroMascotState.Celebrate -> 900
        MiniZeroMascotState.Peek -> 1600
        MiniZeroMascotState.Smiling -> 2200
        MiniZeroMascotState.Love -> 1000
        MiniZeroMascotState.Angry -> 600
        MiniZeroMascotState.Sleeping -> 3000
    }

/** Returns the animated-vector drawable resource used for the given mascot [state]. */
private fun animatedVectorResource(state: MiniZeroMascotState): Int =
    when (state) {
        MiniZeroMascotState.Success,
        MiniZeroMascotState.Celebrate,
        MiniZeroMascotState.Smiling,
        -> R.drawable.avd_mini_zero_success
        MiniZeroMascotState.Error,
        MiniZeroMascotState.Angry,
        -> R.drawable.avd_mini_zero_error
        MiniZeroMascotState.Love -> R.drawable.avd_mini_zero_love
        MiniZeroMascotState.Sleeping -> R.drawable.avd_mini_zero_sleeping
        else -> R.drawable.avd_mini_zero_typing
    }

/** Returns the static vector drawable resource used when animation is disabled for [state]. */
private fun staticVectorResource(state: MiniZeroMascotState): Int =
    when (state) {
        MiniZeroMascotState.Success,
        MiniZeroMascotState.Celebrate,
        MiniZeroMascotState.Smiling,
        -> R.drawable.ic_mini_zero_success_vector
        MiniZeroMascotState.Error,
        MiniZeroMascotState.Angry,
        -> R.drawable.ic_mini_zero_error_vector
        MiniZeroMascotState.Love -> R.drawable.ic_mini_zero_love_vector
        MiniZeroMascotState.Sleeping -> R.drawable.ic_mini_zero_sleeping_vector
        MiniZeroMascotState.Idle,
        MiniZeroMascotState.Thinking,
        MiniZeroMascotState.Typing,
        MiniZeroMascotState.Peek,
        -> R.drawable.ic_mini_zero_vector
    }
