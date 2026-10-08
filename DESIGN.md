# BetterDesk design system

## Context and approved flow

An accountant in a lit office needs help with a computer or phone and is worried about pressing the wrong button. Receiving help occupies the same narrow, quiet column on both devices; a wide desktop does not introduce a dashboard. The app follows the system light/dark preference.

Open invitation -> identify helper -> Allow help -> required platform permission -> active help -> Stop help -> Help ended.

The preceding conversation established this flow. Technical setup is automatic. This initial implementation uses native Compose controls and shared Kotlin UI source for Windows and Android.

## Color and typography

Restrained strategy: tinted neutral surfaces, deep green action color, restrained semantic error red. High contrast text; no pure black/white surfaces. System sans, 30sp primary title, 17sp body, 15sp supporting copy. Shared design tokens live in BetterDeskTheme.

## Layout

Maximum receiving column 440dp. 24dp horizontal spacing, 56dp primary action height, rounded 14dp controls, 8/16/24/40dp spacing rhythm. Secondary Help someone action remains quiet. The remote viewer can occupy the remaining window.

## Interaction

All controls have text labels and native focus/disabled states. No modal permissions imitation: genuine OS prompts appear only when needed. Missing platform capabilities remain explicit. No fabricated connection codes, preview sessions, or fake remote screens.

## First local-network milestone

Helper creates a short-lived invitation with the app and shares it manually. Recipient opens or pastes it, approves, and shares. Links carry a pinned TLS certificate fingerprint and high-entropy one-use secret. Clipboard copying only occurs following an explicit button press.

No cloud image-generation assets or remote fonts are required.
