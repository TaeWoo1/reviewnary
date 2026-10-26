import { useEffect, useState } from "react";

/** Loads data once on mount. Mock-fallback loaders never throw, so `error`
 *  stays null for them (behavior unchanged). Strict loaders (no silent mock)
 *  reject on backend failure, which surfaces here as `error` so the page can
 *  fail closed instead of showing stale/fake data. */
export function useApiData<T>(
  loader: () => Promise<T>,
  deps: unknown[] = [],
  refresh = 0,
): { data: T | null; loading: boolean; error: boolean } {
  const [data, setData] = useState<T | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(false);

  useEffect(() => {
    let active = true;
    setLoading(true);
    setError(false);
    loader()
      .then((d) => {
        if (active) {
          setData(d);
        }
      })
      .catch(() => {
        if (active) {
          setError(true);
        }
      })
      .finally(() => {
        if (active) {
          setLoading(false);
        }
      });
    return () => {
      active = false;
    };
    // Re-runs when `deps` change; callers that omit `deps` keep the once-on-mount
    // behavior. The loader itself is recreated each render, so it isn't a dep.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, deps);

  // `refresh` moving (an open screen told its data changed — lib/screenChanges.tsx) reads again QUIETLY: no loading
  // state, what is on screen stays until the answer lands, and a failed re-read keeps it.
  useEffect(() => {
    if (refresh === 0) return undefined;
    let active = true;
    loader()
      .then((d) => {
        if (active) setData(d);
      })
      .catch(() => undefined);
    return () => {
      active = false;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [refresh]);

  return { data, loading, error };
}
