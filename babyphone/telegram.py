from aiogram import Bot, Dispatcher, executor, types
from aiogram.types.reply_keyboard import ReplyKeyboardMarkup, KeyboardButton
import datetime
import emoji
import logging
import io
import asyncio
import collections
import time
import aiohttp
import numpy as np
import json
import typing
from babyphone.secret import token, whitelistedUsers
from babyphone.image import createImage


class NoiseLevel(typing.NamedTuple):
    level: float
    label: str
    button: str


class Session(object):

    # after 1h of inactivity we'll ask the user if he's still there
    SessionTimeout = 3600
    SessionCheckTimeoutInterval = 10
    # seconds, a user has time to respond until the session will be kicked.
    SessionTimeoutGracePeriod = 120
    # kick time
    SessionTimeoutKick = SessionTimeout + SessionTimeoutGracePeriod

    ButtonStatus = "Send Status"
    ButtonDisconnect = "Disconnect"
    ButtonConnect = "Connect"
    ButtonReconnect = "Reconnect"
    ButtonYes = "Yes"

    ButtonConfig = "Config"
    ButtonConfigBack = "Back"
    ButtonConfigNightMode = "Enable Nightmode"
    ButtonConfigDisableNightMode = "Disable Nightmode"

    ButtonAlarmDisable = "Disable Alarm"
    ButtonAlarmEnable = "Enable Alarm"
    ButtonAlarmSnooze15 = "Snooze Alarm"

    # number of seconds to store volume history
    volumeHistory = 120

    _noiseLevelLow = NoiseLevel(
        level=-0.1, label="low", button="Noise Level Low")
    _noiseLevelMedium = NoiseLevel(level=0, label="medium", button="medium")
    _noiseLevelHigh = NoiseLevel(level=0.1, label="high", button="high")

    _alarmStateEnabled = 'Enabled'
    _alarmStateDisabled = 'Disabled'

    def __init__(self, chatId, bot, babyphone):
        self.connected = False
        self.log = logging.getLogger("babyphone")
        self._chatId = chatId
        self._lastAction = time.time()
        self._babyphone = babyphone
        # if connect checked is true, we have already asked the user to reconnect.
        # this will be set to false, once there was another action
        self._promptReconnect = False
        self._watcher = None
        self._bot = bot

        # store the last message ID send in this session. We will only modify the last
        # message
        self._lastMessageId = 0
        self._lastMessageSent = 0

        self._alarmTrigger = self._noiseLevelMedium
        self._alarmState = self._alarmStateEnabled
        self._alarmLastTrigger = None
        # will be set to a time after which the alarm should be enabled again
        self._alarmEnableTime = None
        # the last message we sent as an alarm
        self._alarmSent = None

        # after this time of no threshold exceeding, we'll return to normal operation
        self._alarmCooldownTimer = 20

        # after this time during alarm, we'll resent the alarm
        self._alarmIntervalTimeout = 10

        self._volumes = []

    def streamRequested(self):
        return False

    def audioRequested(self):
        return False

    async def sendAudioPacket(self, data, pts):
        # TODO handle audio
        pass

    async def sendVideo(self, data):
        # TODO handle video
        pass

    async def sendVolume(self, level):
        self._volumes.append([time.time(), level*100])

        if len(self._volumes) > self.volumeHistory:
            self._volumes = self._volumes[len(
                self._volumes)-self.volumeHistory:]
        await self._checkAlarm()

    async def _checkAlarm(self):

        if self._alarmEnableTime:
            if time.time() > self._alarmEnableTime:
                self._alarmState = self._alarmStateEnabled
                await self._bot.send_message(self._chatId, "Snoozing done. Enabling Alarm again.", reply_markup=self.getMenuKeys())
                self._alarmEnableTime = None

        if len(self._volumes) < 20:
            return

        levels = np.array([x[1] for x in self._volumes])
        quants = np.quantile(levels, [0.5, 0.75])

        threshold = quants[0] + quants[1] + 10

        threshold += threshold*self._alarmTrigger.level

        if levels[-1] > threshold:
            await self.updatedAction()
            # do nothing if the alarm is disabled
            if self._alarmState == self._alarmStateDisabled:
                return

            # we already sent the alarm a few seconds ago
            if self._alarmSent and (datetime.datetime.now() - self._alarmSent['date']).seconds < self._alarmIntervalTimeout:
                return
            else:  # let's send an alarm
                self._alarmSent = await self._bot.send_message(self._chatId,
                                                               emoji.emojize(
                                                                   ":bangbang:Noise Alarm:bangbang:", use_aliases=True),
                                                               reply_markup=self.getAlarmKeys())
        else:
            await self.updatedAction()
            if self._alarmSent and (datetime.datetime.now() - self._alarmSent['date']).seconds > self._alarmCooldownTimer:
                await self._bot.send_message(chat_id=self._chatId,
                                             text=emoji.emojize(
                                                 ":white_check_mark: Seems quiet again", use_aliases=True),
                                             reply_markup=self.getMenuKeys())
                self._alarmSent = None

    async def sendMovement(self, movement):
        # todo handle movement
        pass

    async def sendConfig(self, config):
        # todo handle config
        pass

    async def sendSystemStatus(self, status):
        if not self.connected:
            return

        if status == "shutdown":
            await self.sendGoodBye("Babyphone shutting down. See ya.")
        elif status == "restart":
            await self.sendGoodBye("Babyphone restarting now. You have to reconnect manually.")
        else:
            await self._bot.send_message(self._chatId, "Babyphone updated system status: {}".format(status), reply_markup=self.getMenuKeys())

    def getMenuKeys(self):
        return (ReplyKeyboardMarkup(
            resize_keyboard=True)
            .row(self.ButtonStatus)
            .row(self.ButtonConfig,
                 self.ButtonDisconnect)
        )

    def getAlarmKeys(self):
        return (ReplyKeyboardMarkup(
            resize_keyboard=True)
            .row(self.ButtonStatus)
            .row(self.ButtonAlarmSnooze15, self.ButtonAlarmDisable)
            .row(self.ButtonConfig,
                 self.ButtonDisconnect)
        )

    async def sendMenu(self, message="Hi there! What can I do?"):
        resp = await self._bot.send_message(self._chatId, message,
                                            reply_markup=self.getMenuKeys())

    def getConfigMenu(self):
        return (ReplyKeyboardMarkup(
            resize_keyboard=True)
            .row(self.ButtonConfigBack)
            .row(self.ButtonConfigDisableNightMode if self._babyphone.nightMode else self.ButtonConfigNightMode)
            .row(self.ButtonAlarmDisable if self._alarmState == self._alarmStateEnabled else self.ButtonAlarmEnable,
                 self.ButtonAlarmSnooze15)
            .row(self._noiseLevelLow.button, self._noiseLevelMedium.button, self._noiseLevelHigh.button)
        )

    async def updatedAction(self):
        self._lastAction = time.time()
        self._promptReconnect = False

    async def _send(self, message):
        """ this is the same message as defined in the connection in babyphone.
        TODO: we need a proper interface to avoid calling this protected untyped message
        """
        # currently we don't use the messages (yet)
        pass

    async def handleMessage(self, message):

        if message['text'] != self.ButtonDisconnect:
            await self.updatedAction()

        if message['text'] == self.ButtonDisconnect:
            await self.disconnect()
        elif message['text'] == self.ButtonStatus:
            res = await self._bot.send_chat_action(self._chatId, action="upload_photo")
            if not res:
                self.log.info("Error sending status")
                return

            _, picture = await self._babyphone.motion.updatePicture(highRes=True)
            if picture is not None:
                imageData = createImage(picture, self._volumes)

                await self._bot.send_photo(self._chatId,
                                           types.input_file.InputFile(
                                               imageData,
                                               filename='photo.png',
                                               conf=dict(mime_type='application/octet-stream')),
                                           reply_markup=self.getMenuKeys())

        elif message['text'] in [self.ButtonConnect, self.ButtonReconnect, self.ButtonYes]:
            await self.sendMenu()
        elif message['text'] == self.ButtonConfig:
            await self.showConfig()
        elif message['text'] == self.ButtonConfigBack:
            await self.sendMenu()
        elif message['text'] == self.ButtonConfigNightMode:
            await self._babyphone.setNightMode(True)
            await self.showConfig()
        elif message['text'] == self.ButtonConfigDisableNightMode:
            await self._babyphone.setNightMode(False)
            await self.showConfig()
        elif message['text'] == self.ButtonAlarmDisable:
            if self._alarmState == self._alarmStateEnabled:
                self._alarmState = self._alarmStateDisabled
                self._alarmLastTrigger = None
                self._alarmSent = None
                self._alarmEnableTime = None
                await self._bot.send_message(self._chatId, "Alarm disabled.", reply_markup=self.getMenuKeys())
        elif message['text'] == self.ButtonAlarmEnable:
            if self._alarmState == self._alarmStateDisabled:
                self._alarmState = self._alarmStateEnabled
                self._alarmEnableTime = None
                await self._bot.send_message(self._chatId, "Alarm enabled.", reply_markup=self.getMenuKeys())
        elif message['text'] == self.ButtonAlarmSnooze15:
            # TODO: extract disabling to function
            self._alarmState = self._alarmStateDisabled
            self._alarmLastTrigger = None
            self._alarmSent = None
            # TODO: set real timeout
            self._alarmEnableTime = time.time()+60*15
            await self._bot.send_message(self._chatId, "Snoozing alarm for 15 minutes.", reply_markup=self.getMenuKeys())
        elif message['text'] == self._noiseLevelLow.button:
            self._alarmTrigger = self._noiseLevelLow
            await self._bot.send_message(self._chatId, "Alarm noise level set to " + self._noiseLevelLow.label)
        elif message['text'] == self._noiseLevelMedium.button:
            self._alarmTrigger = self._noiseLevelMedium
            await self._bot.send_message(self._chatId, "Alarm noise level set to " + self._noiseLevelMedium.label)
        elif message['text'] == self._noiseLevelHigh.button:
            self._alarmTrigger = self._noiseLevelHigh
            await self._bot.send_message(self._chatId, "Alarm noise level set to " + self._noiseLevelHigh.label)
        else:
            await self.sendMenu(message="I didn't understand you. Try the buttons")

    async def showConfig(self):
        alarmState = ""
        if self._alarmState == self._alarmStateDisabled:
            if self._alarmEnableTime:
                alarmState = "Snoozing ({} minutes left)".format(
                    int((self._alarmEnableTime - time.time()) / 60))
            else:
                alarmState = "Disabled"
        else:
            alarmState = "Enabled"

        configuration = """
Configuration:
- Alarm {alarmEnabled}
    - Noise level {alarmTrigger}
- Night mode {nightmode}
""".format(nightmode="On" if self._babyphone.nightMode else "Off",
           alarmTrigger=self._alarmTrigger.label,
            alarmEnabled=alarmState)

        await self._bot.send_message(
            self._chatId, configuration, reply_markup=self.getConfigMenu())

    async def sendGoodBye(self, message="GoodBye!"):
        await self._bot.send_message(self._chatId, message, reply_markup=ReplyKeyboardMarkup(
            resize_keyboard=True)
            .row(KeyboardButton(self.ButtonReconnect))
        )

    async def connect(self):
        # already connected
        if self.connected:
            return

        self.connected = True
        await self._babyphone.addConnection(self)

    async def disconnect(self):
        # not connected
        if not self.connected:
            return

        self.connected = False
        self._babyphone.removeConnection(self)
        await self.sendGoodBye()

    def __str__(self):
        return "Telegram connection %s" % self._chatId

    async def checkAlive(self):

        # we're not connected at all, so no need to check
        if not self.connected:
            return (False, False)

        timeSinceLastAction = time.time() - self._lastAction
        if timeSinceLastAction > self.SessionTimeout:
            if timeSinceLastAction < self.SessionTimeoutKick:

                if not self._promptReconnect:
                    await self._bot.send_message(self._chatId, "Still there? You have %d seconds to respond or will be disconnected" % self.SessionTimeoutGracePeriod,
                                                 reply_markup=ReplyKeyboardMarkup(
                                                     resize_keyboard=True)
                                                 .row(KeyboardButton(self.ButtonYes))
                                                 .row(KeyboardButton(self.ButtonDisconnect))
                                                 )
                    self._promptReconnect = True
                return (True, False)
            else:
                return (False, True)

        return (True, False)


class TeleBaby(object):

    def __init__(self, babyphone):
        self._babyphone = babyphone
        self.log = logging.getLogger("babyphone")
        bot = Bot(token=token)
        dp = Dispatcher(bot)

        self._bot = bot
        self._dp = dp
        dp.register_message_handler(self._handleMessage)
        self._sessions = {}

    async def start(self):
        asyncio.create_task(self._watchSessions())
        asyncio.create_task(self._dp.start_polling(
            reset_webhook=False, timeout=60, relax=1, fast=True))

    async def _watchSessions(self):
        while True:
            await asyncio.sleep(Session.SessionCheckTimeoutInterval)
            for chatId in list(self._sessions.keys()):
                session = self._sessions[chatId]

                isAlive, isTimedout = await session.checkAlive()

                # if the session is dead, remove it from the set
                if not isAlive and isTimedout:
                    self.log.info(
                        "removing chat with chatId %s from babyphone", chatId)
                    await session.disconnect()

    async def _handleMessage(self, message):

        self.log.info(message)
        # only handle the message if it is from our whitelisted user
        if message['from']['id'] in whitelistedUsers:
            # just ignore messages from users not in our white list
            await self.handleMessage(message)
        else:
            # we could think to setup an admin user, that gets informed if there are other requests
            self.log.info("ignoring message %s from unauthorized user %s",
                          message, message['from']['id'])

    async def handleMessage(self, message):
        chatId = message['chat']['id']

        session = self._sessions.get(chatId, None)
        if not session:
            session = Session(chatId, self._bot, self._babyphone)
            self._sessions[chatId] = session

        await session.connect()

        await session.handleMessage(message)
