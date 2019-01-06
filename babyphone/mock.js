const WebSocket = require('ws');
const spawn = require('child_process').spawn;

const wss = new WebSocket.Server({
  port: 8088
});

console.log("Created websocket");

wss.on('connection', function connection(ws) {
  console.log("connection opened, will send some random data.");
  var curValue = 0;
  var interval = setInterval(function() {
      var value = Math.random() * 0.1;
      if(value < 0.5 && value < curValue) {
        curValue -= value;
      } else {
        curValue += value;
      }

      try {
        console.log("sending value", curValue);
        ws.send(JSON.stringify({
          "volume": curValue
        }));
      } catch(err) {
        console.log("Error", err, "will shutdown connection");
        clearInterval(interval);
      }
    },
    1000);
});

function shutdown() {
  console.log("shutting down by signal.");
  process.exit(0);
}

process.on('SIGTERM', shutdown);
process.on('SIGINT', shutdown);
process.on('SIGHUP', shutdown);
