const WebSocket = require('ws');
const spawn = require('child_process').spawn;

const wss = new WebSocket.Server({
  port: 8080
});

var vidServer = spawn('./videoserver', [], {
  'env': {
    'GST_DEBUG': 'WARN'
  }
});

var volumeBuffer = [0, 0, 0, 0, 0, 0];
var bufLen = volumeBuffer.length;
var idx = 0;


function appShutdown() {
  console.log("received shutdown. Shutting down the app. See ya.");
  spawn('sudo', ['shutdown', '-h', '0'], {});
}

connections = []

wss.on('connection', function(ws) {
  console.log("Connection requested");
  connections.push(ws);

  // register handle to remove the connections when it's done
  ws.on('close', function() {
    var idx = connections.indexOf(ws);
    if(idx > -1) {
      connections.splice(idx, 1);
    }
  });
  ws.on('message', function(data) {
    console.log("received", data);
    var parsed = JSON.parse(data);

    switch(parsed['action']) {
      case 'shutdown':
        appShutdown();
        break;
      case 'lightson':
        spawn('bash', ['-c', 'echo 1 > /sys/class/gpio/gpio24/value'], {});
        break;
      case 'lightsoff':
        spawn('bash', ['-c', 'echo 0 > /sys/class/gpio/gpio24/value'], {});
        break;
    }
  });

});



vidServer.stdout.on('data', function(data) {
  var dataStr = data.toString()
  dataStr.split("\n").forEach(function(line) {
    consumeData(line);
  });

});

var heartbeatInterval = 2000;
var lastSent = new Date();

function heartbeat() {
  var now = new Date();
  if(now - lastSent > heartbeatInterval) {
    sendToConnections(JSON.stringify({
      'volume': 0.0
    }));
  }
}

function sendToConnections(data) {
  lastSent = new Date();
  connections.forEach(function(el) {
    el.send(data);
  });
}

function consumeData(data) {
  if(!data.startsWith("{")) {
    return;
  }
  try {
    var d = JSON.parse(data);
    var value = d['normrms'];
    volumeBuffer[idx] = value;
    if(idx == 0) {
      sendToConnections(JSON.stringify({
        "volume": volumeBuffer.reduce((a, b) => a + b, 0) / bufLen
      }));
    }
    idx = (idx + 1) % bufLen;
  } catch(err) {
    console.log(err, data.toString());
  }
}

vidServer.stdout.on('data', function(data) {
  if(data.toString().startsWith("{")) {
    return
  }
  console.log("process output", data.toString());
});

vidServer.stderr.on('data', function(data) {
  console.log('stderr: ' + data.toString());
});

vidServer.on('exit', function(code) {
  console.log('child process exited with code ' + code);
});


function shutdown() {
  console.log("shutting down by signal.");
  vidServer.kill('SIGTERM');
  process.exit(0);
}

setTimeout(function() {
  spawn('bash', ['-c', 'echo 24 > /sys/class/gpio/export'], {});
}, 1000);

setTimeout(function() {
  spawn('bash', ['-c', 'echo out > /sys/class/gpio/gpio24/direction'], {});
}, 2000);

process.on('SIGTERM', shutdown);
process.on('SIGINT', shutdown);
process.on('SIGHUP', shutdown);
