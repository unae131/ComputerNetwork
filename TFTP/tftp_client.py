import socket
import struct
import os
import random
import sys

class TFTP_Client:
    # port
    PORT = 69

    # Opcode
    RRQ     = 1 # Read request
    WRQ     = 2 # Write request
    DATA    = 3 # Data
    ACK     = 4 # Acknwledgment
    ERROR   = 5 # Error

    # type
    TYPE_OCTET = b'octet\0'
    TYPE_NETASCII = b'netascii\0'

    def __init__(self, _mode, _hostIP, _cmd, _srcPath):
        # socket setting
        self._sock = socket.socket(family=socket.AF_INET, type=socket.SOCK_DGRAM)
        self._sock.settimeout(5)
        self._host = (_hostIP, self.PORT)

        # process cmd
        if _cmd == "GET":
            self.GET(_srcPath, self.TYPE_OCTET if _mode else self.TYPE_NETASCII) # RRQ

        elif _cmd == "PUT":
            self.PUT(_srcPath, self.TYPE_OCTET if _mode else self.TYPE_NETASCII) # WRQ

        else:
            self._ERROR()

    def GET(self, _srcPath, _mode):
        # make a packet
        RRQpacket = self._make_RQpacket(self.RRQ, _srcPath, _mode)

        if self._send(RRQpacket) == False:
            self._ERROR()
            return

        if _mode: # Octet
            f = open(_srcPath, 'wb')
        else: # netASCII
            f = open(_srcPath, 'w')

        seq = 1
        while(True):
            rcvPacket = self._recv()
            if rcvPacket == None:
                self._ERROR()
                break

            parsed = self._parse_packet(rcvPacket)

            if  parsed[0] == self.DATA and parsed[1] == seq: # in-order data packet
                # blockNum = parsed[1]
                data = parsed[2]
                dataSize = parsed[3]
                f.write(data)

                # send ack
                if self._send(self._make_ACKpacket(seq)) == False:
                    self._ERROR()
                    break

                seq += 1

                if dataSize < 512: # last packet
                    break

            elif parsed[0] == self.DATA and parsed[1] == seq - 1: # retransmit ACK
                # send ack
                if self._send(self._make_ACKpacket(seq-1)) == False:
                    self._ERROR()
                    break

            elif parsed[0] == self.ERROR: # error packet
                ErrCode = parsed[1]
                ErrMsg = parsed[2]
                print(str(ErrCode) + " " + ErrMsg)
                break

        f.close()
        return

    def PUT(self, _srcPath, _mode):
        # make a wrq packet
        WRQpacket = self._make_RQpacket(self.WRQ, _srcPath, _mode)

        if self._send(WRQpacket) == False:
            self._ERROR()
            return

        # receive response
        while(True):
            rcvPacket = self._recv()
            if rcvPacket == None:
                self._ERROR()
                return
            else:
                parsed = _parse_packet(rcvPacket)
                if parsed[0] == self.ACK and parsed[1] == 0:
                    break

        if _mode: # Octet
            f = open(_srcPath, 'rb')
        else: # netASCII
            f = open(_srcPath, 'r')

        # read a file
        data = f.read()
        chunk = len(data) / 512
        offset = len(data) % 512

        seq = 1
        while(seq  <= chunk):
            # send a data packet
            dataPacket = self._make_DATApacket(seq, data[seq*512 : seq * 512 + 512])

            if self._send(dataPacket) == False:
                self._ERROR()
                break

            # receive response
            rcvPacket = self._recv()
            if rcvPacket == None:
                self._ERROR()
                break

            parsed = self._parse_packet(rcvPacket)

            if parsed[0] == self.ACK and parsed[1] == seq: # ack
                seq += 1

            elif parsed[0] == self.ACK and parsed[1] == seq-1: # have to retransmit
                seq -= 1

            elif parsed[0] == self.ERROR: # error packet
                ErrCode, ErrMsg = parsed[1:3]
                print(str(ErrCode) + " " + ErrMsg)
                break

        f.close()
        return

    def _send(self, _packet):
        try:
            self._sock.sendto(_packet, self._host)
            return True

        except Exception as e:
            print(e)
            return False

    def _recv(self, _size=1024):
        try:
            print("test")
            recv = self._sock.recvfrom(_size)
            print(recv)
            return recv[0]

        except Exception as e:
            print(e)
            return None

    # make a packet
    def _make_RQpacket(self, opcode, fileName, mode):
        # opcode | fileName | 0 | mode | 0
        return struct.pack('>H', opcode) + (fileName + '\0').encode() + mode

    def _make_DATApacket(self, blockNum, data):
        # opcode | blockNum | data
        return struct.pack('>H', self.DATA) + struct.pack('>H', blockNum) + data # size of data is not bigger than 512bytes

    def _make_ACKpacket(self, blockNum):
        # opcode | blockNum
        return struct.pack('>H', self.ACK) + struct.pack('>H', blockNum)    

    # packet 파싱
    def _parse_packet(self, _packet):
        try:
            opcode = struct.unpack('>H', _packet[:2])[0]

            if opcode == self.DATA: # op | block# | data
                blockNum = struct.unpack('>H', _packet[2:4])[0]
                data = _packet[4:].decode()
                dataSize = len(_packet[4:])
                return opcode, blockNum, data, dataSize

            elif opcode == self.ACK: # op | block#
                blockNum = struct.unpack('>H', _packet[2:4])[0]
                return opcode, blockNum

            elif opcode == self.ERROR: # op | ErrCode | ErrMsg | 0
                errCode = struct.unpack('>H', _packet[2:4])[0]
                errMsg = _packet[4:].decode()
                errMsg = errMsg[ : len(errMsg) - 1]
                return opcode, errCode, errMsg

        except Exception as e:
            print(e)
            return None   

    def _ERROR(self):
        print('''Connection Failed.
        TFTP [-i] host [GET | PUT] fileName
        ''')

if __name__ == "__main__":
    if len(sys.argv) == 6 and sys.argv[1] == "-i":
        TFTP_Client(True, sys.argv[2], sys.argv[3], sys.argv[4])

    elif len(sys.argv) == 5:
        TFTP_Client(False, sys.argv[1],sys.argv[2], sys.argv[3])

    else:
        print("TFTP.py [-i] host [GET | PUT] fileName")