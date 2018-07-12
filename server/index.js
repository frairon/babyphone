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

wss.on('connection', function connection(ws) {
  console.log("Connection requested");
  vidServer.stdout.on('data', function(data) {
    var dataStr = data.toString()
    dataStr.split("\n").forEach(function(line){
      consumeData(ws, line);
    });

  });
});

function consumeData(ws, data){
  if(!data.startsWith("{")) {
    return;
  }
  try {
    var d = JSON.parse(data);
    var value = d['normrms'];
    volumeBuffer[idx] = value;
    if(idx == 0) {
      ws.send(JSON.stringify({
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

process.on('SIGTERM', shutdown);
process.on('SIGINT', shutdown);
process.on('SIGHUP', shutdown);
