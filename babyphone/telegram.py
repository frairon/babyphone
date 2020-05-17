from aiogram import Bot, Dispatcher, executor, types
from aiogram.types.reply_keyboard import ReplyKeyboardMarkup, KeyboardButton
import logging
import io
import asyncio
import collections
import time
import aiohttp
import json
from babyphone.secret import token, whitelistedUsers
from babyphone.image import createImage
import cv2


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

    # number of seconds to store volume history
    volumeHistory = 120

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

        self._alarm = None
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
        self._volumes.append([time.time(), level])

        if len(self._volumes) > self.volumeHistory:
            self._volumes = self._volumes[len(
                self._volumes)-self.volumeHistory:]

    async def sendMovement(self, movement):
        # todo handle movement
        pass

    async def sendConfig(self, config):
        # todo handle config
        pass

    async def sendSystemStatus(self, status):
        # todo handle systemstatus
        pass

    def getMenuKeys(self):
        return (ReplyKeyboardMarkup(
            resize_keyboard=True)
            .row(self.ButtonStatus)
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

        if message['text'] == self.ButtonDisconnect:
            await self.disconnect()
            await self.sendGoodBye()
        elif message['text'] == self.ButtonStatus:
            res = await self._bot.send_chat_action(self._chatId, action="upload_photo")
            if not res:
                self.log.info("Error sending status")
                return

            _, picture = await self._babyphone.motion.updatePicture(highRes=True)
            if picture is not None:
                imageData = createImage(picture, self._volumes)
                # imageData = cv2.imencode(".png", picture)[1].tostring()
                await self._bot.send_photo(self._chatId,
                                           types.input_file.InputFile(
                                               imageData,
                                               filename='photo.png',
                                               conf=dict(mime_type='application/octet-stream')),
                                           reply_markup=self.getMenuKeys())

            await self.updatedAction()
        elif message['text'] in [self.ButtonConnect, self.ButtonReconnect, self.ButtonYes]:
            await self.updatedAction()
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
        else:
            await self.sendMenu(message="I didn't understand you. Try the buttons")

    async def showConfig(self):

        configuration = """
Configuration:
- Night mode {nightmode}
""".format(nightmode="On" if self._babyphone.nightMode else "Off")

        await self._bot.send_message(
            self._chatId, configuration, reply_markup=self.getConfigMenu())

    async def sendGoodBye(self):
        await self._bot.send_message(self._chatId, "Goodbye!", reply_markup=ReplyKeyboardMarkup(
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

    def __str__(self):
        return "Telegram connection %s" % self._chatId

    async def checkAlive(self):

        # we're not connected at all, so no need to check
        if not self.connected:
            return False

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
                return True
            else:
                return False

        return True


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
            reset_webhook=False, timeout=20, relax=0.1, fast=True))

    async def _watchSessions(self):
        while True:
            await asyncio.sleep(Session.SessionCheckTimeoutInterval)
            for chatId in list(self._sessions.keys()):
                session = self._sessions[chatId]

                isAlive = await session.checkAlive()

                # if the session is dead, remove it from the set
                if not isAlive:
                    self.log.info(
                        "removing chat with chatId %s from babyphone", chatId)
                    await session.disconnect()
                    await session.sendGoodBye()

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
