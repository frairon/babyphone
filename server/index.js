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

wss.on('connection', function connection(ws) {
  vidServer.stdout.on('data', function(data) {
    try {
      var d = JSON.parse(data);
      ws.send(JSON.stringify({
        "volume": d['normrms']
      }));
    } catch(err) {
      console.log(data);
    }
  });
});

vidServer.stdout.on('data', function(data) {
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
