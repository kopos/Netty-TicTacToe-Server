/*
 * Copyright 2011 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package com.tictactoe.server;

import static org.jboss.netty.handler.codec.http.HttpHeaders.*;
import static org.jboss.netty.handler.codec.http.HttpHeaders.Names.*;
import static org.jboss.netty.handler.codec.http.HttpMethod.*;
import static org.jboss.netty.handler.codec.http.HttpResponseStatus.*;
import static org.jboss.netty.handler.codec.http.HttpVersion.*;

import org.jboss.netty.buffer.ChannelBuffer;
import org.jboss.netty.buffer.ChannelBuffers;
import org.jboss.netty.channel.ChannelFuture;
import org.jboss.netty.channel.ChannelFutureListener;
import org.jboss.netty.channel.ChannelHandlerContext;
import org.jboss.netty.channel.ExceptionEvent;
import org.jboss.netty.channel.MessageEvent;
import org.jboss.netty.channel.SimpleChannelUpstreamHandler;
import org.jboss.netty.handler.codec.http.DefaultHttpResponse;
import org.jboss.netty.handler.codec.http.HttpHeaders;
import org.jboss.netty.handler.codec.http.HttpRequest;
import org.jboss.netty.handler.codec.http.HttpResponse;
import org.jboss.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import org.jboss.netty.handler.codec.http.websocketx.PingWebSocketFrame;
import org.jboss.netty.handler.codec.http.websocketx.PongWebSocketFrame;
import org.jboss.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import org.jboss.netty.handler.codec.http.websocketx.WebSocketFrame;
import org.jboss.netty.handler.codec.http.websocketx.WebSocketServerHandshaker;
import org.jboss.netty.handler.codec.http.websocketx.WebSocketServerHandshakerFactory;
import org.jboss.netty.logging.InternalLogger;
import org.jboss.netty.logging.InternalLoggerFactory;
import org.jboss.netty.util.CharsetUtil;

import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;

import com.google.gson.Gson;
import com.tictactoe.game.Board;
import com.tictactoe.game.Game;
import com.tictactoe.game.Game.PlayerLetter;
import com.tictactoe.game.Game.Status;
import com.tictactoe.game.Player;

import com.tictactoe.server.message.GameOverMessageBean;
import com.tictactoe.server.message.GameOverMessageBean.Result;
import com.tictactoe.server.message.HandshakeMessageBean;
import com.tictactoe.server.message.IncomingMessageBean;
import com.tictactoe.server.message.OutgoingMessageBean;
import com.tictactoe.server.message.TurnMessageBean;
import com.tictactoe.server.message.TurnMessageBean.Turn;

/**
 * Handles handshakes and messages
 */
public class TicTacToeServerHandler extends SimpleChannelUpstreamHandler {
    private static final InternalLogger logger = InternalLoggerFactory.getInstance(TicTacToeServerHandler.class);

    private static final String WEBSOCKET_PATH = "/websocket";

    private WebSocketServerHandshaker handshaker;
    static Map<Integer, Game> games = new HashMap<Integer, Game>();

    @Override
    public void messageReceived(ChannelHandlerContext ctx, MessageEvent e) throws Exception {
        Object msg = e.getMessage();
        if (msg instanceof HttpRequest) {
            handleHttpRequest(ctx, (HttpRequest) msg);
        } else if (msg instanceof WebSocketFrame) {
            handleWebSocketFrame(ctx, (WebSocketFrame) msg);
        }
    }

    private void handleHttpRequest(ChannelHandlerContext ctx, HttpRequest req) throws Exception {
        // Allow only GET methods.
        if (req.getMethod() != GET) {
            sendHttpResponse(ctx, req, new DefaultHttpResponse(HTTP_1_1, FORBIDDEN));
            return;
        }

        // Send the demo page and favicon.ico
        if (req.getUri().equals("/")) {
            HttpResponse res = new DefaultHttpResponse(HTTP_1_1, OK);

            ChannelBuffer content = WebSocketServerIndexPage.getContent(getWebSocketLocation(req));

            res.setHeader(CONTENT_TYPE, "text/html; charset=UTF-8");
            setContentLength(res, content.readableBytes());

            res.setContent(content);
            sendHttpResponse(ctx, req, res);
            return;
        } else if (req.getUri().equals("/favicon.ico")) {
            HttpResponse res = new DefaultHttpResponse(HTTP_1_1, NOT_FOUND);
            sendHttpResponse(ctx, req, res);
            return;
        }

        // Handshake
        WebSocketServerHandshakerFactory wsFactory = new WebSocketServerHandshakerFactory(
                this.getWebSocketLocation(req), null, false);
        this.handshaker = wsFactory.newHandshaker(req);
        if (this.handshaker == null) {
            wsFactory.sendUnsupportedWebSocketVersionResponse(ctx.getChannel());
        } else {
            this.handshaker.handshake(ctx.getChannel(), req);
            initGame(ctx);
        }
    }

    private void handleWebSocketFrame(ChannelHandlerContext ctx, WebSocketFrame frame) {

        // Check for closing frame
        if (frame instanceof CloseWebSocketFrame) {
            this.handshaker.close(ctx.getChannel(), (CloseWebSocketFrame) frame);
            return;
        } else if (frame instanceof PingWebSocketFrame) {
            ctx.getChannel().write(new PongWebSocketFrame(frame.getBinaryData()));
            return;
        } else if (!(frame instanceof TextWebSocketFrame)) {
            throw new UnsupportedOperationException(String.format("%s frame types not supported", frame.getClass()
                    .getName()));
        }

        // Send the uppercase string back.
        String request = ((TextWebSocketFrame) frame).getText();
        handleGameFrame(ctx, ((TextWebSocketFrame) frame));
        logger.debug(String.format("Channel %s received %s", ctx.getChannel().getId(), request));
        //ctx.getChannel().write(new TextWebSocketFrame("Server says: " + request.toUpperCase()));
        //ctx.getChannel().write(new TextWebSocketFrame(gson.toJson(response.toString()));
    }

	private void handleGameFrame(ChannelHandlerContext ctx, TextWebSocketFrame frame) {
		
		Gson gson = new Gson();
		IncomingMessageBean message = gson.fromJson(frame.getText(), IncomingMessageBean.class);
		
		Game game = games.get(message.getGameId());
		Player opponent = game.getOpponent(message.getPlayer());
		Player player = game.getPlayer(PlayerLetter.valueOf(message.getPlayer()));
		
		// Mark the cell the player selected.
		game.markCell(message.getGridIdAsInt(), player.getLetter());
		
		// Get the status for the current game.
		boolean winner = game.isPlayerWinner(player.getLetter());
		boolean tied = game.isTied();
		
		// Respond to the opponent in order to update their screen.
		String responseToOpponent = new OutgoingMessageBean(player.getLetter().toString(), message.getGridId(), winner, tied).toJson();		
		opponent.getChannel().write(new TextWebSocketFrame(responseToOpponent));
		
		// Respond to the player to let them know they won.
		GameOverMessageBean you_win = new GameOverMessageBean(Result.YOU_WIN);
		GameOverMessageBean tie    = new GameOverMessageBean(Result.TIED);
		if (winner) {
			player.getChannel().write(new TextWebSocketFrame(you_win.toJson()));
		} else if (tied) {
			player.getChannel().write(new TextWebSocketFrame(tie.toJson()));
		}
	}

    private void sendHttpResponse(ChannelHandlerContext ctx, HttpRequest req, HttpResponse res) {
        // Generate an error page if response status code is not OK (200).
        if (res.getStatus().getCode() != 200) {
            res.setContent(ChannelBuffers.copiedBuffer(res.getStatus().toString(), CharsetUtil.UTF_8));
            setContentLength(res, res.getContent().readableBytes());
        }

        // Send the response and close the connection if necessary.
        ChannelFuture f = ctx.getChannel().write(res);
        if (!isKeepAlive(req) || res.getStatus().getCode() != 200) {
            f.addListener(ChannelFutureListener.CLOSE);
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, ExceptionEvent e) throws Exception {
        e.getCause().printStackTrace();
        e.getChannel().close();
    }

    private String getWebSocketLocation(HttpRequest req) {
        return "ws://" + req.getHeader(HttpHeaders.Names.HOST) + WEBSOCKET_PATH;
    }

	/**
	 * Initializes a game. Finds an open game for a player (if another player is already waiting) or creates a new game.
	 * 
	 * @param ctx
	 */
	private void initGame(ChannelHandlerContext ctx) {
		// Try to find a game waiting for a player. If one doesn't exist, create a new one.
		Game game = findGame();
		
		// Create a new instance of player and assign their channel for WebSocket communications.
		Player player = new Player(ctx.getChannel());
		
		// Add the player to the game.
		Game.PlayerLetter letter = game.addPlayer(player);
		
		// Add the game to the collection of games.
		games.put(game.getId(), game);
		
		// Send confirmation message to player with game ID and their assigned letter (X or O)
		HandshakeMessageBean message = new HandshakeMessageBean(game.getId(), letter.toString());
		ctx.getChannel().write(new TextWebSocketFrame(message.toJson()));
		
		// If the game has begun we need to inform the players. Send them a "turn" message (either "waiting" or "your_turn")
		if (game.getStatus() == Game.Status.IN_PROGRESS) {	
			TurnMessageBean your_turn = new TurnMessageBean(Turn.YOUR_TURN);
			TurnMessageBean waiting   = new TurnMessageBean(Turn.WAITING);
			game.getPlayer(PlayerLetter.X).getChannel().write(new TextWebSocketFrame(your_turn.toJson()));
			game.getPlayer(PlayerLetter.O).getChannel().write(new TextWebSocketFrame(waiting.toJson()));
		}
	}
	
	/**
	 * Finds an open game for a player (if another player is waiting) or creates a new game.
	 *
	 * @return Game
	 */
	private Game findGame() {		
		// Find an existing game and return it
		for (Game g : games.values()) {
			if (g.getStatus().equals(Game.Status.WAITING)) {
				return g;
			}
		}
		
		// Or return a new game
		return new Game();
	}
 
}
