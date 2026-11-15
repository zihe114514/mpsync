package yun.shan.mpsync.javaagent;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * 各版本共用的原版服务器字节码注入流程。
 */
public final class JavaAgentTransformer implements ClassFileTransformer {
    private static final String SERVER = "net/minecraft/server/MinecraftServer";
    private static final String BUNDLER = "net/minecraft/bundler/Main";
    private static final String HOOKS = "yun/shan/mpsync/javaagent/Hooks";
    private final Mapping m;

    public JavaAgentTransformer(Mapping mapping) {
        this.m = mapping;
    }

    @Override
    public byte[] transform(ClassLoader loader, String name, Class<?> type, ProtectionDomain domain, byte[] bytes) {
        if (name == null || !(name.equals(BUNDLER) || name.equals(SERVER) || name.equals(m.playerList())
            || name.equals(m.commands()) || name.equals(m.listener()) || name.equals(m.incoming())
            || name.equals(m.outgoing()))) return null;
        ClassReader reader = new ClassReader(bytes);
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public MethodVisitor visitMethod(int access, String method, String descriptor, String signature, String[] exceptions) {
                return new MethodVisitor(Opcodes.ASM9, super.visitMethod(access, method, descriptor, signature, exceptions)) {
                    void hook(String methodName, String desc) {
                        super.visitMethodInsn(Opcodes.INVOKESTATIC, HOOKS, methodName, desc, false);
                    }

                    @Override
                    public void visitTypeInsn(int opcode, String target) {
                        if (name.equals(BUNDLER) && method.equals("run") && opcode == Opcodes.NEW && target.equals("java/net/URLClassLoader")) {
                            super.visitVarInsn(Opcodes.ALOAD, 6);
                            super.visitLdcInsn("mpsync.agent.url");
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/System", "getProperty", "(Ljava/lang/String;)Ljava/lang/String;", false);
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, "java/net/URI", "create", "(Ljava/lang/String;)Ljava/net/URI;", false);
                            super.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/net/URI", "toURL", "()Ljava/net/URL;", false);
                            super.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/List", "add", "(Ljava/lang/Object;)Z", true);
                            super.visitInsn(Opcodes.POP);
                        }
                        super.visitTypeInsn(opcode, target);
                    }

                    @Override
                    public void visitMethodInsn(int opcode, String owner, String called, String desc, boolean itf) {
                        if (m.codecOwner() != null && method.equals("<clinit>")
                            && (name.equals(m.incoming()) || name.equals(m.outgoing()))
                            && owner.equals(m.codecOwner()) && called.equals(m.codecMethod())
                            && (m.codecDescriptor() == null || desc.equals(m.codecDescriptor()))) {
                            super.visitInsn(name.equals(m.incoming()) ? Opcodes.ICONST_1 : Opcodes.ICONST_0);
                            hook("addPayloads", "(Ljava/util/List;Z)Ljava/util/List;");
                        }
                        super.visitMethodInsn(opcode, owner, called, desc, itf);
                    }

                    @Override
                    public void visitInsn(int opcode) {
                        if (opcode == Opcodes.RETURN) {
                            if (name.equals(SERVER) && method.equals(m.tick()) && descriptor.equals("(Ljava/util/function/BooleanSupplier;)V")) {
                                super.visitVarInsn(Opcodes.ALOAD, 0);
                                hook("tick", "(L" + SERVER + ";)V");
                            }
                            if (name.equals(SERVER) && method.equals(m.stop()) && descriptor.equals("()V"))
                                hook("shutdown", "()V");
                            if (name.equals(m.playerList()) && method.equals(m.join()) && (m.joinDescriptor() == null || descriptor.equals(m.joinDescriptor()))) {
                                super.visitVarInsn(Opcodes.ALOAD, 2);
                                hook("join", "(L" + m.player() + ";)V");
                            }
                            if (name.equals(m.playerList()) && method.equals(m.quit()) && descriptor.equals("(L" + m.player() + ";)V")) {
                                super.visitVarInsn(Opcodes.ALOAD, 1);
                                hook("quit", "(L" + m.player() + ";)V");
                            }
                            if (name.equals(m.commands()) && method.equals("<init>")) {
                                super.visitVarInsn(Opcodes.ALOAD, 0);
                                hook("commands", "(L" + m.commands() + ";)V");
                            }
                            if (name.equals(m.listener()) && method.equals(m.packet()) && descriptor.equals("(L" + m.incoming() + ";)V")) {
                                super.visitVarInsn(Opcodes.ALOAD, 0);
                                super.visitVarInsn(Opcodes.ALOAD, 1);
                                hook("packet", "(L" + m.listener() + ";L" + m.incoming() + ";)V");
                            }
                        }
                        super.visitInsn(opcode);
                    }
                };
            }
        }, 0);
        return writer.toByteArray();
    }

    /**
     * 混淆版本提供运行时类名、方法名和描述符；未混淆版本直接提供官方名称。
     */
    public record Mapping(String playerList, String commands, String listener, String player,
                          String incoming, String outgoing, String tick, String stop,
                          String join, String joinDescriptor, String quit, String packet,
                          String codecOwner, String codecMethod, String codecDescriptor) {
    }
}

