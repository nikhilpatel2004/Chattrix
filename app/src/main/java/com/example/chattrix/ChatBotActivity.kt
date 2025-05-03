package com.example.chattrix

import Message
import android.os.Bundle
import android.util.Log
import android.widget.EditText
import android.widget.ImageView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.ai.client.generativeai.GenerativeModel
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ChatBotActivity : AppCompatActivity() {

    private lateinit var chatRecyclerView: RecyclerView
    private lateinit var messageBox: EditText
    private lateinit var sendButton: ImageView
    private lateinit var messageAdapter: MessageAdapter
    private lateinit var messageList: ArrayList<Message>
    private lateinit var mDbRef: DatabaseReference
    private lateinit var generativeModel: GenerativeModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_chat)

        supportActionBar?.title = "Chattrix Assistant"

        generativeModel = GenerativeModel(
            modelName = "gemini-1.5-pro",
            apiKey = Constants.apiKey
        )

        // UI setup
        chatRecyclerView = findViewById(R.id.chatRecyclerView)
        messageBox = findViewById(R.id.messageBox)
        sendButton = findViewById(R.id.sentButton)

        messageList = ArrayList()
        messageAdapter = MessageAdapter(this, messageList)

        chatRecyclerView.layoutManager = LinearLayoutManager(this)
        chatRecyclerView.adapter = messageAdapter

        mDbRef = FirebaseDatabase.getInstance().reference
        val currentUserUid = FirebaseAuth.getInstance().currentUser?.uid ?: "unknown"
        val chatPath = mDbRef.child("chats").child("bot_$currentUserUid").child("messages")

        // ✅ Show welcome message in UI only (not Firebase)
        val welcomeMessage = Message(
            message = "Yo! I'm Chattrix AI ⚡ — your geeky sidekick in the cloud.\nNeed answers? Code? Ideas?\nLet's get rolling 🚀 — just type it out!",
            senderId = "bot"
        )
        messageList.add(welcomeMessage)
        messageAdapter.notifyItemInserted(0)

        // ✅ Load all previous messages from Firebase
        chatPath.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                messageList.removeAll { it.senderId != "bot" } // Keep only welcome message
                for (postSnapshot in snapshot.children) {
                    val message = postSnapshot.getValue(Message::class.java)
                    message?.let { messageList.add(it) }
                }
                messageAdapter.notifyDataSetChanged()
                chatRecyclerView.scrollToPosition(messageList.size - 1)
            }

            override fun onCancelled(error: DatabaseError) {
                Toast.makeText(this@ChatBotActivity, "Error loading messages", Toast.LENGTH_SHORT).show()
            }
        })

        // ✅ Handle user sending message
        sendButton.setOnClickListener {
            val userMessage = messageBox.text.toString().trim()
            if (userMessage.isNotEmpty()) {
                val userMessageObject = Message(message = userMessage, senderId = currentUserUid)
                chatPath.push().setValue(userMessageObject)

                // Add temporary "Thinking..." message
                val loadingMessage = Message(message = "Thinking...", senderId = "bot")
                chatPath.push().setValue(loadingMessage)

                getGeminiResponse(userMessage, chatPath)
                messageBox.setText("")
            }
        }
    }

    private fun getGeminiResponse(userMessage: String, chatPath: DatabaseReference) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val chat = generativeModel.startChat()
                val response = chat.sendMessage(userMessage)
                val botResponse = response.text ?: "I'm sorry, I couldn't generate a response."

                withContext(Dispatchers.Main) {
                    // Remove the last "Thinking..." message
                    chatPath.orderByKey().limitToLast(1).addListenerForSingleValueEvent(object : ValueEventListener {
                        override fun onDataChange(snapshot: DataSnapshot) {
                            for (child in snapshot.children) {
                                child.ref.removeValue()
                            }
                        }

                        override fun onCancelled(error: DatabaseError) {}
                    })

                    // Add Gemini's response
                    val botMessageObject = Message(message = botResponse, senderId = "bot")
                    chatPath.push().setValue(botMessageObject)
                }

            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@ChatBotActivity, "Error: ${e.message}", Toast.LENGTH_SHORT).show()

                    // Remove thinking message
                    chatPath.orderByKey().limitToLast(1).addListenerForSingleValueEvent(object : ValueEventListener {
                        override fun onDataChange(snapshot: DataSnapshot) {
                            for (child in snapshot.children) {
                                child.ref.removeValue()
                            }
                        }

                        override fun onCancelled(error: DatabaseError) {}
                    })

                    // Add error message
                    val errorMessage = Message(
                        message = "I'm sorry, I encountered an error. Please try again.",
                        senderId = "bot"
                    )
                    chatPath.push().setValue(errorMessage)
                }
            }
        }
    }
}
