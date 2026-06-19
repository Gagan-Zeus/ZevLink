package com.zevclip.sender

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ZevAssistantCommandParserTest {
    @Test fun parsesCall() = assertEquals(
        ZevAssistantCommand.Call("Mom"),
        ZevAssistantCommandParser.parse("call Mom")
    )

    @Test fun parsesMessageToRecipient() = assertEquals(
        ZevAssistantCommand.SendMessage("Mom", "I am running late"),
        ZevAssistantCommandParser.parse("send a message to Mom saying I am running late")
    )

    @Test fun parsesRecipientFirstMessage() = assertEquals(
        ZevAssistantCommand.SendMessage("Riya", "meet me outside"),
        ZevAssistantCommandParser.parse("send Riya a text that says meet me outside")
    )

    @Test fun parsesDirectTextCommand() = assertEquals(
        ZevAssistantCommand.SendMessage("Dad", "please call me"),
        ZevAssistantCommandParser.parse("text Dad that please call me")
    )

    @Test fun parsesBodyFirstMessage() = assertEquals(
        ZevAssistantCommand.SendMessage("Aman", "on my way"),
        ZevAssistantCommandParser.parse("send on my way to Aman")
    )

    @Test fun parsesCompactTextCommand() = assertEquals(
        ZevAssistantCommand.SendMessage("Mom", "reached home"),
        ZevAssistantCommandParser.parse("message Mom reached home")
    )

    @Test fun parsesMessageToRecipientWithoutConnector() = assertEquals(
        ZevAssistantCommand.SendMessage("Mom", "reached home"),
        ZevAssistantCommandParser.parse("send a message to Mom reached home")
    )

    @Test fun parsesSmsCommand() = assertEquals(
        ZevAssistantCommand.SendMessage("9876543210", "call me back"),
        ZevAssistantCommandParser.parse("send an SMS to 9876543210 saying call me back")
    )

    @Test fun parsesTellCommandAsMessage() = assertEquals(
        ZevAssistantCommand.SendMessage("Aman", "I will be there in ten minutes"),
        ZevAssistantCommandParser.parse("tell Aman that I will be there in ten minutes")
    )

    @Test fun parsesWhatsAppToRecipient() = assertEquals(
        ZevAssistantCommand.SendWhatsAppMessage("Mom", "I am running late"),
        ZevAssistantCommandParser.parse("send a WhatsApp message to Mom saying I am running late")
    )

    @Test fun parsesRecipientFirstWhatsApp() = assertEquals(
        ZevAssistantCommand.SendWhatsAppMessage("Riya", "meet me outside"),
        ZevAssistantCommandParser.parse("send Riya a WhatsApp that says meet me outside")
    )

    @Test fun parsesDirectWhatsAppCommand() = assertEquals(
        ZevAssistantCommand.SendWhatsAppMessage("Dad", "please call me"),
        ZevAssistantCommandParser.parse("whatsapp Dad that please call me")
    )

    @Test fun parsesBodyFirstWhatsAppCommand() = assertEquals(
        ZevAssistantCommand.SendWhatsAppMessage("Aman", "on my way"),
        ZevAssistantCommandParser.parse("send on my way to Aman on WhatsApp")
    )

    @Test fun parsesTellOnWhatsAppCommand() = assertEquals(
        ZevAssistantCommand.SendWhatsAppMessage("Aman", "I will be there in ten minutes"),
        ZevAssistantCommandParser.parse("tell Aman on WhatsApp that I will be there in ten minutes")
    )

    @Test fun rejectsMessageWithoutBody() = assertTrue(
        ZevAssistantCommandParser.parse("send a message to Mom") is ZevAssistantCommand.Unknown
    )

    @Test fun rejectsWhatsAppWithoutBody() = assertTrue(
        ZevAssistantCommandParser.parse("send a WhatsApp to Mom") is ZevAssistantCommand.Unknown
    )

    @Test fun parsesPlayMusicControl() = assertEquals(
        ZevAssistantCommand.MediaControl(MediaAction.Play),
        ZevAssistantCommandParser.parse("play music")
    )

    @Test fun parsesResumeMusicControl() = assertEquals(
        ZevAssistantCommand.MediaControl(MediaAction.Play),
        ZevAssistantCommandParser.parse("resume music")
    )

    @Test fun parsesPauseMusicControl() = assertEquals(
        ZevAssistantCommand.MediaControl(MediaAction.Pause),
        ZevAssistantCommandParser.parse("pause music")
    )

    @Test fun parsesNextSongControl() = assertEquals(
        ZevAssistantCommand.MediaControl(MediaAction.Next),
        ZevAssistantCommandParser.parse("next song")
    )

    @Test fun parsesSkipTrackControl() = assertEquals(
        ZevAssistantCommand.MediaControl(MediaAction.Next),
        ZevAssistantCommandParser.parse("skip to the next track")
    )

    @Test fun parsesPlaySongWithoutApp() = assertEquals(
        ZevAssistantCommand.PlayMedia("Blinding Lights", null),
        ZevAssistantCommandParser.parse("play Blinding Lights")
    )

    @Test fun parsesPlaySongWithApp() = assertEquals(
        ZevAssistantCommand.PlayMedia("Blinding Lights", "Spotify"),
        ZevAssistantCommandParser.parse("play Blinding Lights on Spotify")
    )

    @Test fun parsesPlayTheSongWithApp() = assertEquals(
        ZevAssistantCommand.PlayMedia("Shape of You", "YouTube Music"),
        ZevAssistantCommandParser.parse("play the song Shape of You in YouTube Music")
    )

    @Test fun parsesListenToSong() = assertEquals(
        ZevAssistantCommand.PlayMedia("Naatu Naatu", null),
        ZevAssistantCommandParser.parse("listen to Naatu Naatu")
    )

    @Test fun parsesMacLock() = assertEquals(
        ZevAssistantCommand.MacRemote(MacRemoteAction.Lock),
        ZevAssistantCommandParser.parse("lock my Mac")
    )

    @Test fun parsesMacSleep() = assertEquals(
        ZevAssistantCommand.MacRemote(MacRemoteAction.Sleep),
        ZevAssistantCommandParser.parse("put the Mac to sleep")
    )

    @Test fun parsesMacDisplayOff() = assertEquals(
        ZevAssistantCommand.MacRemote(MacRemoteAction.SleepDisplay),
        ZevAssistantCommandParser.parse("turn off Mac screen")
    )

    @Test fun parsesMacRestart() = assertEquals(
        ZevAssistantCommand.MacRemote(MacRemoteAction.Restart),
        ZevAssistantCommandParser.parse("reboot computer")
    )

    @Test fun parsesMacShutdown() = assertEquals(
        ZevAssistantCommand.MacRemote(MacRemoteAction.Shutdown),
        ZevAssistantCommandParser.parse("shut down the Mac")
    )

    @Test fun parsesMacLogout() = assertEquals(
        ZevAssistantCommand.MacRemote(MacRemoteAction.Logout),
        ZevAssistantCommandParser.parse("log out of my Mac")
    )

    @Test fun parsesMacMute() = assertEquals(
        ZevAssistantCommand.MacRemote(MacRemoteAction.Mute),
        ZevAssistantCommandParser.parse("mute Mac audio")
    )

    @Test fun parsesMacVolumeDown() = assertEquals(
        ZevAssistantCommand.MacRemote(MacRemoteAction.VolumeDown),
        ZevAssistantCommandParser.parse("lower volume on Mac")
    )

    @Test fun parsesMacVolumeUp() = assertEquals(
        ZevAssistantCommand.MacRemote(MacRemoteAction.VolumeUp),
        ZevAssistantCommandParser.parse("Mac volume plus")
    )

    @Test fun parsesMacPreviousTrack() = assertEquals(
        ZevAssistantCommand.MacRemote(MacRemoteAction.Previous),
        ZevAssistantCommandParser.parse("previous track on Mac")
    )

    @Test fun parsesMacPlayPause() = assertEquals(
        ZevAssistantCommand.MacRemote(MacRemoteAction.PlayPause),
        ZevAssistantCommandParser.parse("play pause on Mac")
    )

    @Test fun parsesMacNextTrack() = assertEquals(
        ZevAssistantCommand.MacRemote(MacRemoteAction.Next),
        ZevAssistantCommandParser.parse("next song on Mac")
    )

    @Test fun keepsPlainNextSongAsAndroidMedia() = assertEquals(
        ZevAssistantCommand.MediaControl(MediaAction.Next),
        ZevAssistantCommandParser.parse("next song")
    )

    @Test fun rejectsStopwatchCommand() = assertTrue(
        ZevAssistantCommandParser.parse("start stopwatch") is ZevAssistantCommand.Unknown
    )

    @Test fun parsesApp() = assertEquals(
        ZevAssistantCommand.OpenApp("Spotify"),
        ZevAssistantCommandParser.parse("open Spotify app")
    )

    @Test fun parsesNavigation() = assertEquals(
        ZevAssistantCommand.Navigate("New Delhi railway station"),
        ZevAssistantCommandParser.parse("navigate to New Delhi railway station")
    )

    @Test fun parsesCompoundTimer() = assertEquals(
        ZevAssistantCommand.SetTimer(3900),
        ZevAssistantCommandParser.parse("set a timer for 1 hour and 5 minutes")
    )

    @Test fun parsesTwelveHourAlarm() = assertEquals(
        ZevAssistantCommand.SetAlarm(19, 30),
        ZevAssistantCommandParser.parse("set an alarm for 7:30 pm")
    )

    @Test fun parsesDottedPmAlarm() = assertEquals(
        ZevAssistantCommand.SetAlarm(19, 30),
        ZevAssistantCommandParser.parse("set an alarm for 7:30 p.m.")
    )

    @Test fun parsesSimpleMorningAlarm() = assertEquals(
        ZevAssistantCommand.SetAlarm(7, 0),
        ZevAssistantCommandParser.parse("set alarm for 7 AM")
    )

    @Test fun parsesWakeMeUpAlarm() = assertEquals(
        ZevAssistantCommand.SetAlarm(6, 30),
        ZevAssistantCommandParser.parse("wake me up at 6:30 AM")
    )

    @Test fun parsesSpokenWakeMeUpAlarm() = assertEquals(
        ZevAssistantCommand.SetAlarm(6, 30),
        ZevAssistantCommandParser.parse("wake me up at six thirty am")
    )

    @Test fun parsesEveningAlarmPhrase() = assertEquals(
        ZevAssistantCommand.SetAlarm(19, 0),
        ZevAssistantCommandParser.parse("create an alarm at 7 in the evening")
    )

    @Test fun parsesCancelAlarm() = assertEquals(
        ZevAssistantCommand.DismissAlarm(),
        ZevAssistantCommandParser.parse("cancel alarm")
    )

    @Test fun parsesCancelSpecificAlarm() = assertEquals(
        ZevAssistantCommand.DismissAlarm(7, 0, false),
        ZevAssistantCommandParser.parse("cancel alarm for 7 AM")
    )

    @Test fun parsesShowAlarms() = assertEquals(
        ZevAssistantCommand.ShowAlarms,
        ZevAssistantCommandParser.parse("show alarms")
    )

    @Test fun rejectsUnsupportedCommand() = assertTrue(
        ZevAssistantCommandParser.parse("tell me a joke") is ZevAssistantCommand.Unknown
    )
}
