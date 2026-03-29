# App Blocker

This is an Android app to manage and enforce screen time. Normal Android Digital Wellbeing sucks and is too easily bypassed.

**Play Protect may block this app. This is because the app requires AccessiblityService to know which app you are currently in to take over your screen and block apps that have reached their quota. This is a false positive.**

# Features

- Create a block set
  - Quotas for the block set are shared across apps in the block set
  - Time based rules to selectively enforce block set during important times
  - Interventions to require user action before allowing an app to open
- Overrides to allow access to apps after their quota has been reached
- Lockdowns to prevent access to any apps within block sets for a set period
- Password/random code protection for settings, override, and lockdown cancellation to prevent users from bypassing settings

# Walkthrough

## Creating and using a block set
- Create a block set
- Add apps to it
- Set a quota
- Set a time window if you do not want it active all day
- Pick an intervention if you want opening the app to require effort

Lockdowns block all apps within block sets. Overrides allow access within the time limit if enabled.

# Device nonsense
Pixel and emulator behavior is usually straightforward. Other phones may run into issues because Android skins usually have weird active package behaviours.

# Install

**This ship requires an Android device.**
Install the apk in the release.

OR

Clone this repo into Android Studio and run it inside Android Studio.

## Setup after install
- Enable accessibility access
- Enable usage access
- Enable draw over other apps
- Disable battery optimization for this app if your phone is aggressive about killing background services

# Demo
https://youtu.be/FgcRY_i01nM
