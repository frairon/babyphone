const WebSocket = require('ws');

const wss = new WebSocket.Server({
  port: 8080
});

wss.on('connection', function connection(ws) {
  ws.on('message', function incoming(message) {
    console.log('received: %s', message);
  });

  ws.send('something');
});


var spawn = require('child_process').spawn,
  ls = spawn('ls', ['-lh', '/usr']);

ls.stdout.on('data', function(data) {
  console.log('stdout: ' + data.toString());
});

ls.stderr.on('data', function(data) {
  console.log('stderr: ' + data.toString());
});

ls.on('exit', function(code) {
  console.log('child process exited with code ' + code.toString());
});
