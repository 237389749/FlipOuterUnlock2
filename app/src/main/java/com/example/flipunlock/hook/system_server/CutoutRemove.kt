package com.example.flipunlock.hook.system_server

import android.graphics.Insets
import android.graphics.Path
import android.graphics.Rect
import com.example.flipunlock.hook.util.*
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam
import java.io.File

/**
 * Remove the outer-screen display cutout so windows lay out across the full
 * 1208px width instead of being clipped to the cutout-safe area (~810px).
 *
 * Approach (B): Parser.parse() in system_server zeros ALL cutout specs for
 * fullscreen. The real bounds are cached to a temp file before zeroing.
 * In the camera process, hookApp() reads the cached bounds and hooks
 * Display.getCutout() to return a reconstructed DisplayCutout with non-empty
 * bounding rects (preventing camera NPE in CamLayoutManagerImpl).
 *
 * Hook #1 (Parser.parse, system_server): zero ALL specs, cache real bounds.
 * Hook #2 (Display.getCutout, camera): return reconstructed real DisplayCutout.
 * Hook #3 (getLayoutInDisplayCutoutMode → ALWAYS): defense-in-depth.
 *
 * Toggle: persist.flipunlock.display.cutout (default true)
 */
object CutoutRemove {

    // Cache file stores: "L,R,T,B,Bi,ri,Ti,Bi" (8 ints: leftBound, rightBound,
    // topBound, bottomBound rects + insets left,right,top,bottom)
    private const val CACHE_FILE = "/data/local/tmp/.flip_cutout_bounds"

    fun hook(param: SystemServerStartingParam) {
        if (!Config.displayCutout) {
            log("CutoutRemove: DISABLED by persist.flipunlock.display.cutout")
            return
        }
        log("CutoutRemove: setting up in system_server")
        safeHook("CutoutRemove") {
            hookCutoutParser(param.classLoader)
            forceCutoutModeAlways(param.classLoader)
        }
    }

    /** Called from onPackageReady for camera process. */
    fun hookApp(param: PackageReadyParam) {
        if (!Config.displayCutout) return
        log("CutoutRemove: hookApp in ${param.packageName}")
        safeHook("CutoutRemove") {
            val cl = param.classLoader
            val bounds = readCachedBounds()
            if (bounds != null) {
                val (lb, rb, tb, bb, insets) = bounds
                log("CutoutRemove: cached bounds L=$lb R=$rb T=$tb B=$bb insets=$insets")
                val realCutout = constructDisplayCutout(cl, lb, rb, tb, bb, insets)
                if (realCutout != null) {
                    hookDisplayGetCutout(cl, realCutout)
                    log("CutoutRemove: Display.getCutout → real cutout (camera)")
                } else {
                    log("CutoutRemove: failed to construct DisplayCutout")
                }
            } else {
                log("CutoutRemove: no cached bounds found")
            }
            forceCutoutModeAlways(cl)
        }
    }

    // ── #1 CutoutSpecification.Parser.parse() → zero ALL specs + cache real ──
    private fun hookCutoutParser(classLoader: ClassLoader) {
        runCatching {
            val parserClass = classLoader.loadClass(
                "android.view.CutoutSpecification\$Parser")
            val parseMethod = parserClass.method("parse", String::class.java)
            hook(parseMethod, after { chain, result ->
                val spec = result ?: return@after result
                val svgString = chain.args[0] as? String
                // Cache real bounds BEFORE zeroing (outer screen spec)
                if (svgString != null &&
                    (svgString.contains("M 604,664") || svgString.contains("@bind_right_cutout"))) {
                    cacheRealBounds(spec)
                }
                // Zero everything for fullscreen
                spec.setField("mLeftBound", Rect(0, 0, 0, 0))
                spec.setField("mTopBound", Rect(0, 0, 0, 0))
                spec.setField("mRightBound", Rect(0, 0, 0, 0))
                spec.setField("mBottomBound", Rect(0, 0, 0, 0))
                spec.setField("mInsets", Insets.of(0, 0, 0, 0))
                spec.setField("mPath", Path())
                result
            })
            log("CutoutRemove: Parser.parse → zero ALL specs + cache outer bounds")
        }.onFailure { log("CutoutRemove: Parser.parse hook failed", it) }
    }

    // ── #2 Display.getCutout() → real DisplayCutout (camera only) ──
    private fun hookDisplayGetCutout(classLoader: ClassLoader, realCutout: Any) {
        runCatching {
            val method = android.view.Display::class.java.getMethod("getCutout")
            hook(method, replaceResult(realCutout))
        }.onFailure { log("CutoutRemove: Display.getCutout hook failed", it) }
    }

    // ── #3 WindowLayoutStubImpl.getLayoutInDisplayCutoutMode() → ALWAYS (3) ──
    private fun forceCutoutModeAlways(classLoader: ClassLoader) {
        runCatching {
            val cls = classLoader.loadClass("android.view.WindowLayoutStubImpl")
            val method = cls.method(
                "getLayoutInDisplayCutoutMode",
                android.view.WindowManager.LayoutParams::class.java)
            hook(method, replaceResult(3))
            log("CutoutRemove: getLayoutInDisplayCutoutMode → ALWAYS (3)")
        }.onFailure { log("CutoutRemove: getLayoutInDisplayCutoutMode hook failed", it) }
    }

    // ── Cache real bounds to file (system_server, before zeroing) ──
    private fun cacheRealBounds(spec: Any) {
        runCatching {
            val lb = spec.getField("mLeftBound") as? Rect ?: Rect()
            val rb = spec.getField("mRightBound") as? Rect ?: Rect()
            val tb = spec.getField("mTopBound") as? Rect ?: Rect()
            val bb = spec.getField("mBottomBound") as? Rect ?: Rect()
            val ins = spec.getField("mInsets") as? Insets ?: Insets.of(0, 0, 0, 0)
            // Format: "lL,lR,lT,lB|rL,rR,rT,rB|tL,tR,tT,tB|bL,bR,bT,bB|iL,iR,iT,iB"
            val line = "${lb.left},${lb.right},${lb.top},${lb.bottom}" +
                "|${rb.left},${rb.right},${rb.top},${rb.bottom}" +
                "|${tb.left},${tb.right},${tb.top},${tb.bottom}" +
                "|${bb.left},${bb.right},${bb.top},${bb.bottom}" +
                "|${ins.left},${ins.right},${ins.top},${ins.bottom}"
            File(CACHE_FILE).writeText(line)
            log("CutoutRemove: cached bounds: $line")
        }.onFailure { log("CutoutRemove: cacheRealBounds failed", it) }
    }

    // ── Read cached bounds from file (camera process) ──
    private data class CutoutBounds(
        val leftBound: Rect, val rightBound: Rect,
        val topBound: Rect, val bottomBound: Rect,
        val insets: Insets
    )

    private fun readCachedBounds(): CutoutBounds? {
        return runCatching {
            val f = File(CACHE_FILE)
            if (!f.exists()) return@runCatching null
            val parts = f.readText().split("|")
            if (parts.size < 5) return@runCatching null
            fun parseRect(s: String): Rect {
                val v = s.split(",").map { it.toInt() }
                return Rect(v[0], v[2], v[1], v[3]) // left,top,right,bottom
            }
            fun parseInsets(s: String): Insets {
                val v = s.split(",").map { it.toInt() }
                return Insets.of(v[0], v[2], v[1], v[3]) // left,top,right,bottom
            }
            CutoutBounds(
                leftBound = parseRect(parts[0]),
                rightBound = parseRect(parts[1]),
                topBound = parseRect(parts[2]),
                bottomBound = parseRect(parts[3]),
                insets = parseInsets(parts[4])
            )
        }.onFailure { log("CutoutRemove: readCachedBounds failed", it) }.getOrNull()
    }

    // ── Construct DisplayCutout via reflection ──
    private fun constructDisplayCutout(
        classLoader: ClassLoader,
        lb: Rect, rb: Rect, tb: Rect, bb: Rect, insets: Insets
    ): Any? {
        val dcClass = classLoader.loadClass("android.view.DisplayCutout")
        // Try NONE field first
        runCatching {
            dcClass.getDeclaredField("NONE").apply { isAccessible = true }
                .let { return it.get(null) }
        }
        // Enumerate constructors
        for (ctor in dcClass.declaredConstructors.sortedBy { it.parameterCount }) {
            runCatching {
                ctor.isAccessible = true
                val args = ctor.parameterTypes.map { t ->
                    when {
                        t == Int::class.javaPrimitiveType -> 0
                        t == Long::class.javaPrimitiveType -> 0L
                        t == Boolean::class.javaPrimitiveType -> false
                        t == Float::class.javaPrimitiveType -> 0f
                        t == Rect::class.java -> lb
                        t == Insets::class.java -> insets
                        t == Path::class.java -> Path()
                        t == List::class.java -> listOf(lb, tb, rb, bb)
                        else -> null
                    }
                }.toTypedArray()
                return ctor.newInstance(*args)
            }
        }
        return null
    }
}
