import socket
import struct
import os
import random


class TFTP_Test():
    # Opcode
    RRQ     = 1 # Read request
    WRQ     = 2 # Write request
    DATA    = 3 # Data
    ACK     = 4 # Acknwledgment
    ERROR   = 5 # Error
    
    # type
    TYPE_OCTET = b'octet\0'
    TYPE_NETASCII = b'netascii\0'

    def __init__(self, _dstIP, _port=69):
        self._sock = socket.socket(family=socket.AF_INET, type=socket.SOCK_DGRAM)
        self._sock.settimeout(3)
        self._dest = (_dstIP, _port)

    def _send(self, _packet):
        try:
            self._sock.sendto(_packet, self._dest)
            return True
        except Exception as e:
            print(e)
            return False

    def _recv(self, _size=1024):
        try:
            recv = self._sock.recvfrom(_size)
            return recv[0]
        except Exception as e:
            print(e)
            return None

    # opcode 파싱
    def _parse_opcode(self, _data):
        try:
            opcode = struct.unpack('>H', _data[:2])
            return opcode[0]
        except Exception as e:
            print(e)
            return None
    
    # 파일 업로드
    def GET(self, _srcfilename):
        Opcode = struct.pack('>H', 1)
        Source_File = (_srcfilename + '\0').encode()
        Type = self.TYPE_NETASCII
    
        packet = Opcode + Source_File + Type #\0?

        if self._send(packet) == False:
            return False
        
        data = self._recv()
        if data == None:
            return False

        Opcode = self._parse_opcode(data)
        if Opcode != self.DATA:
            return False

        return True
    
    # 파일 다운로드
    def PUT(self, _srcfilename):
        Opcode = struct.pack('>H', 2)
        Source_File = (_srcfilename + '\0').encode()
        Type = self.TYPE_NETASCII
        
        packet = Opcode + Source_File + Type
        
        if self._send(packet) == False:
            return False

        data = self._recv()
        if data == None:
            return False

        Opcode = self._parse_opcode(data)
        if Opcode != self.ACK:
            return False

        return True


if __name__ == "__main__":
    print(os.getcwd())

    tftp = TFTP_Test('172.16.50.82')
    
    print(tftp.PUT('test.txt'))
    
    #print(tftp.GET('test.txt'))