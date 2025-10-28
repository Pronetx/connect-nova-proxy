# Makefile for mod_nova_sonic FreeSWITCH module

# Module name
MODULE_NAME = mod_nova_sonic

# Source files
SOURCES = src/mod_nova_sonic.c

# FreeSWITCH paths (adjust as needed)
FS_INCLUDES = /usr/local/freeswitch/include/freeswitch
FS_MODULES = /usr/local/freeswitch/mod

# Compiler flags
CC = gcc
CFLAGS = -fPIC -O2 -Wall -Werror -I$(FS_INCLUDES) -I/usr/include
LDFLAGS = -shared -Xlinker -x

# Required libraries (minimal - just standard C libraries)
LIBS =

# Output
TARGET = $(MODULE_NAME).so

# Build rules
all: $(TARGET)

$(TARGET): $(SOURCES)
	@echo "Building $(MODULE_NAME)..."
	$(CC) $(CFLAGS) $(LDFLAGS) -o $(TARGET) $(SOURCES) $(LIBS)
	@echo "Build complete: $(TARGET)"

install: $(TARGET)
	@echo "Installing $(TARGET) to $(FS_MODULES)/"
	sudo cp $(TARGET) $(FS_MODULES)/
	@echo "Module installed. Restart FreeSWITCH or run 'reload $(MODULE_NAME)'"

clean:
	rm -f $(TARGET) *.o

uninstall:
	sudo rm -f $(FS_MODULES)/$(TARGET)

.PHONY: all install clean uninstall
