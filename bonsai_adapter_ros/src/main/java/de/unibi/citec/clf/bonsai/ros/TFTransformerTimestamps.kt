package de.unibi.citec.clf.bonsai.ros

import com.google.common.base.Preconditions
import de.unibi.citec.clf.bonsai.core.exception.TransformException
import de.unibi.citec.clf.bonsai.core.time.Time
import de.unibi.citec.clf.bonsai.ros.helper.FrameTransformTree
import de.unibi.citec.clf.bonsai.util.CoordinateTransformer
import de.unibi.citec.clf.btl.Transform
import de.unibi.citec.clf.btl.data.common.Timestamp
import de.unibi.citec.clf.btl.ros.MsgTypeFactory
import de.unibi.citec.clf.btl.units.TimeUnit
import geometry_msgs.TransformStamped
import org.apache.log4j.Logger
import org.ros.exception.RosMessageRuntimeException
import org.ros.message.MessageListener
import org.ros.namespace.GraphName
import org.ros.node.ConnectedNode
import org.ros.node.topic.Subscriber
import org.ros.rosjava_geometry.FrameTransform
import tf2_msgs.TFMessage
import javax.media.j3d.Transform3D
import javax.vecmath.Quat4d
import javax.vecmath.Vector3d

/**
 * Created by lruegeme on 1/18/18.
 */
class TFTransformerTimestamps(gn: GraphName, gn2: GraphName) : CoordinateTransformer(), MessageListener<List<TransformStamped>> {
    private val logger = Logger.getLogger(javaClass)
    private val currentTree: FrameTransformTree = FrameTransformTree()

    private inner class TfOneNode(gn: GraphName, ml: MessageListener<List<TransformStamped>>) : RosNode(), MessageListener<tf.tfMessage> {
        private val nodeName: GraphName
        private val listener : MessageListener<List<TransformStamped>>

        private var subscriberTf: Subscriber<tf.tfMessage>? = null
        override fun onStart(connectedNode: ConnectedNode) {
            try {
                subscriberTf = connectedNode.newSubscriber("/tf", tf.tfMessage._TYPE)
                subscriberTf?.addMessageListener(this, 10)
            } catch (e : RosMessageRuntimeException) {
                logger.warn("disable tf one (tf/tfMessage) support", e)
            }

            initialized = true
        }

        override fun destroyNode() {
            subscriberTf?.shutdown()
        }

        override fun getDefaultNodeName(): GraphName {
            return nodeName
        }

        override fun onNewMessage(tfMessage: tf.tfMessage) {
            val transforms = tfMessage.transforms
            listener.onNewMessage(transforms)
        }

        init {
            initialized = false
            nodeName = gn
            listener = ml
        }
    }
    private inner class TfNode(gn: GraphName, ml: MessageListener<List<TransformStamped>>) : RosNode(), MessageListener<TFMessage> {
        private val nodeName: GraphName
        private val listener : MessageListener<List<TransformStamped>>

        private var subscriberTf: Subscriber<TFMessage>? = null
        private var subscriberTfStatic: Subscriber<TFMessage>? = null
        override fun onStart(connectedNode: ConnectedNode) {

            subscriberTf = connectedNode.newSubscriber("/tf", TFMessage._TYPE)
            subscriberTfStatic = connectedNode.newSubscriber("/tf_static", TFMessage._TYPE)
            subscriberTf?.addMessageListener(this, 10)
            subscriberTfStatic?.addMessageListener(this, 1)
            initialized = true
        }

        override fun destroyNode() {
            subscriberTf?.shutdown()
            subscriberTfStatic?.shutdown()
        }

        override fun getDefaultNodeName(): GraphName {
            return nodeName
        }

        override fun onNewMessage(tfMessage: TFMessage) {
            val transforms = tfMessage.transforms
            listener.onNewMessage(transforms)
        }

        init {
            initialized = false
            nodeName = gn
            listener = ml
        }
    }

    val node: RosNode
    val node2: RosNode

    @Throws(TransformException::class)
    override fun lookup(from: String, to: String, time: Long): Transform {
        logger.debug("lookup $from -> $to @ $time")
        val ftf = getTransform(from, to, time)
        val translation = ftf.transform.translation
        val rotationAndScale = ftf.transform.rotationAndScale
        val quat = Quat4d(rotationAndScale.x, rotationAndScale.y,
                rotationAndScale.z, rotationAndScale.w)
        val vec = Vector3d(translation.x, translation.y, translation.z)

        //todo check scale
        val tf3d = Transform3D(quat, vec, 1.0)
        return Transform(tf3d, from, to, 0)
    }

    init {
        node = TfOneNode(gn,this)
        node2 = TfNode(gn2,this)
    }

    @Throws(TransformException::class)
    fun getTransform(source: String?, target: String?, time: Long): FrameTransform {
        val rostime = MsgTypeFactory.fromTimestamp(Timestamp(time, TimeUnit.MILLISECONDS))
        val transform = currentTree.transform(source, target, rostime)
        logger.trace("fetch tf: $transform")
        return transform ?: throw TransformException(source, target, time)
    }

    override fun onNewMessage(transforms: List<TransformStamped>) {
        for (ts in transforms) {
            logger.trace("got tf " + ts.header.frameId + " -> " + ts.childFrameId)
            currentTree.update(ts)
        }
    }


}