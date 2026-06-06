using UnityEngine;

/// <summary>
/// The two kits in play this match — the local player's and the contrasting
/// opponent's — set from Kotlin's playerAppearance bridge message and read by
/// ShotManager to dress the shooter for whichever side takes each kick.
/// Local cosmetics only; nothing here is on-chain.
/// </summary>
public static class MatchKits
{
    public struct Kit
    {
        public Color jersey;
        public Color shorts;
        public Color socks;
    }

    // Defaults until a playerAppearance message arrives, deliberately contrasting
    // so the per-round swap is still visible in a no-message fallback (e.g. the
    // editor, or PvAI before the bridge fires).
    public static Kit Local = new Kit
    {
        jersey = new Color(0.000f, 0.408f, 0.278f), // Mexico green
        shorts = new Color(0.960f, 0.960f, 0.960f),
        socks  = new Color(0.808f, 0.067f, 0.149f),
    };

    public static Kit Opponent = new Kit
    {
        jersey = new Color(0.106f, 0.227f, 0.541f), // contrasting royal blue
        shorts = new Color(0.960f, 0.960f, 0.960f),
        socks  = new Color(0.106f, 0.227f, 0.541f),
    };
}
