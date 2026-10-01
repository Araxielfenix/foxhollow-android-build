package dev.encounter.borealis;

import android.content.Context;

import dev.encounter.aurora.AuroraSurface;

/**
 * Surface used by the Foxhollow port.
 *
 * Foxhollow does not use Borealis' UI layer, so this is Aurora's surface unchanged; it exists so
 * the activity keeps a single place to change if Borealis ever needs surface-level behaviour.
 */
public class BorealisSurface extends AuroraSurface {
    public BorealisSurface(Context context) {
        super(context);
    }
}
