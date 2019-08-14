import asyncio
import json
import socket


class DiscoveryServer(asyncio.DatagramProtocol):

    def __init__(self, *args, **kwargs):
        asyncio.DatagramProtocol.__init__(*args, **kwargs)
        self._host = socket.gethostbyaddr(self._get_ip())

    def _get_ip(self):
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        try:
            # doesn't even have to be reachable
            s.connect(('10.255.255.255', 1))
            IP = s.getsockname()[0]
        except Exception as e:
            print("error was", e)
            IP = '127.0.0.1'
        finally:
            s.close()
        return IP

    def connection_made(self, transport):
        self._transport = transport

    def datagram_received(self, data, addr):
        message = json.loads(data.decode('utf-8'))
        print("got message", data, message)
        if message.get('action', None) == 'discover':
            self.advertise()

    def advertise(self):
        self._transport.sendto(json.dumps(dict(
            action='advertise',
            host=self._host[0],
            ip = self._host[2][0])).encode('utf-8'),
            ('<broadcast>', 31634))

def createDiscoveryServer(loop):
    print("creating socket")
    endpoint = loop.create_datagram_endpoint(
        DiscoveryServer, local_addr=('0.0.0.0', 31634),
        allow_broadcast=True,)
    print("endpoint", endpoint)
    transport, protocol = loop.run_until_complete(endpoint)
    print("started endpoint successfully")
